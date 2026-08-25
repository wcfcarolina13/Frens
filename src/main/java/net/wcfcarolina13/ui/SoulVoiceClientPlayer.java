package net.wcfcarolina13.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoicePcm;
import net.wcfcarolina13.network.SoulVoicePayload;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Client-side playback for soul-generated voice lines: chunk reassembly (Task 3's
 * {@link SoulVoicePcm.Reassembly}, confined here under its own lock) feeding a dedicated
 * OpenAL device + context that lives entirely on its own daemon thread.
 *
 * <p>Plain {@code alcMakeContextCurrent} is process-global, not per-thread — using it here
 * would steal context currency away from Minecraft's own sound thread and could silently
 * break all game audio. Instead this class requires the {@code ALC_EXT_thread_local_context}
 * extension and binds our context to {@code frens-soul-voice-al} via
 * {@link EXTThreadLocalContext#alcSetThreadContext(long)}, never {@code alcMakeContextCurrent}.
 * If the extension isn't available, playback is permanently disabled for the session rather
 * than falling back to the global call. Owning our own device/context this way means this
 * class never touches Minecraft's own sound engine or its ALC context — every {@code AL10}/
 * {@code ALC10} call in this file runs on {@code frens-soul-voice-al},
 * reached only through {@link #alTask(Runnable)}. The client tick handler
 * ({@link #onClientTick(MinecraftClient)}) does cheap capture only (camera pose, bot
 * positions, option volumes) and hands the result to that thread as a single task.
 */
public final class SoulVoiceClientPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("soul-voice-client");

    /**
     * Flat client-side attenuation for RADIO-mode lines. The server doesn't send a radio
     * gain, so this is baked in here for v1 rather than plumbed through the payload.
     */
    private static final float RADIO_GAIN = 0.6f;

    private record ActiveVoice(UUID botId, int source, int buffer, byte mode) {
    }

    private static final BlockingQueue<Runnable> AL_TASKS = new LinkedBlockingQueue<>();

    /** Not thread-safe (per Task 3's contract) — every access is confined under this lock. */
    private static final SoulVoicePcm.Reassembly REASSEMBLY = new SoulVoicePcm.Reassembly();

    /** Mutated only on the AL thread; read from the client thread to plan tick captures. */
    private static final Map<UUID, ActiveVoice> ACTIVE = new ConcurrentHashMap<>();

    /** Last known bot position, held while the entity is temporarily unresolvable. */
    private static final Map<UUID, Vec3d> LAST_POSITIONS = new ConcurrentHashMap<>();

    private static volatile long device;
    private static volatile long context;
    private static volatile boolean started;
    private static volatile Thread alThread;

    /** Set permanently for the session if thread-local AL context isn't available, or device
     *  / context creation otherwise fails — every subsequent {@link #play} call no-ops. */
    private static volatile boolean playbackDisabled;

    /** AL-thread-confined; the last volume factor observed by a tick, used as the gain a
     *  freshly started voice plays at before the next tick refreshes it. */
    private static float masterPlayersVolume = 1.0f;

    private SoulVoiceClientPlayer() {
    }

    /** Called from any thread (network receiver thread) — enqueues, never blocks on AL. */
    public static void onPayload(SoulVoicePayload payload) {
        long now = System.currentTimeMillis();
        Optional<byte[]> pcm;
        synchronized (REASSEMBLY) {
            pcm = REASSEMBLY.accept(payload.correlationId(), payload.chunkIndex(),
                    payload.chunkCount(), payload.data(), now);
        }
        pcm.ifPresent(bytes -> alTask(() ->
                play(payload.botId(), payload.mode(), payload.sampleRate(), bytes)));
    }

    /** Called every client tick on the client thread. Cheap capture only; work is enqueued. */
    public static void onClientTick(MinecraftClient client) {
        if (client == null) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (REASSEMBLY) {
            REASSEMBLY.expireStale(now);
        }

        if (ACTIVE.isEmpty() || client.world == null || client.player == null
                || client.gameRenderer == null || client.options == null) {
            return;
        }

        Camera camera = client.gameRenderer.getCamera();
        Vec3d listenerPos = camera.getCameraPos();
        Vec3d forward = client.player.getRotationVector(camera.getPitch(), camera.getYaw());
        float volumeFactor = client.options.getSoundVolume(SoundCategory.MASTER)
                * client.options.getSoundVolume(SoundCategory.PLAYERS);

        Map<UUID, Vec3d> botPositions = new HashMap<>();
        for (UUID botId : ACTIVE.keySet()) {
            Entity entity = client.world.getEntity(botId);
            Vec3d pos = entity != null ? entity.getEntityPos() : LAST_POSITIONS.get(botId);
            if (pos != null) {
                botPositions.put(botId, pos);
                LAST_POSITIONS.put(botId, pos);
            }
        }

        alTask(() -> tick(listenerPos, forward, volumeFactor, botPositions));
    }

    /** Stops and deletes every active voice. Called on disconnect. */
    public static void stopAll() {
        if (!started) {
            return;
        }
        alTask(SoulVoiceClientPlayer::stopAllOnAlThread);
    }

    /**
     * Stops everything and tears down the AL context + device. Enqueues directly to
     * {@link #AL_TASKS} rather than going through {@link #alTask(Runnable)} — {@code started}
     * is about to flip false, and routing through {@code alTask} would spin up a second
     * worker thread racing the one that's already draining these exact cleanup tasks.
     */
    public static void shutdown() {
        Thread thread;
        synchronized (SoulVoiceClientPlayer.class) {
            if (!started) {
                return;
            }
            thread = alThread;
            started = false;
        }
        AL_TASKS.offer(() -> {
            stopAllOnAlThread();
            destroyContext();
        });
        if (thread != null) {
            AL_TASKS.offer(thread::interrupt);
        }
    }

    // ---- AL-thread-confined below this line -------------------------------------------

    private static void play(UUID botId, byte mode, int sampleRate, byte[] pcm) {
        if (playbackDisabled) {
            return;
        }
        ensureContext();
        if (context == 0L) {
            return;
        }
        stopVoiceOnAlThread(botId);

        Vec3d lastKnown = LAST_POSITIONS.get(botId);
        // A bot whose position we've never resolved client-side can't be pinned in space —
        // play it listener-relative (radio) instead of parking it at the OpenAL origin.
        byte effectiveMode = (mode == SoulVoicePayload.MODE_POSITIONAL && lastKnown == null)
                ? SoulVoicePayload.MODE_RADIO
                : mode;

        int buffer = AL10.alGenBuffers();
        ByteBuffer data = BufferUtils.createByteBuffer(pcm.length);
        data.put(pcm).flip();
        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, data, sampleRate);

        int source = AL10.alGenSources();
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        if (effectiveMode == SoulVoicePayload.MODE_RADIO) {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
        } else {
            AL10.alSource3f(source, AL10.AL_POSITION,
                    (float) lastKnown.x, (float) lastKnown.y, (float) lastKnown.z);
        }
        AL10.alSourcef(source, AL10.AL_GAIN, currentGain(effectiveMode));
        AL10.alSourcePlay(source);
        ACTIVE.put(botId, new ActiveVoice(botId, source, buffer, effectiveMode));
    }

    private static void tick(Vec3d listenerPos, Vec3d forward, float volumeFactor,
                              Map<UUID, Vec3d> botPositions) {
        masterPlayersVolume = volumeFactor;

        boolean anyPositional = false;
        for (ActiveVoice voice : ACTIVE.values()) {
            if (voice.mode() == SoulVoicePayload.MODE_POSITIONAL) {
                anyPositional = true;
                break;
            }
        }
        if (anyPositional) {
            AL10.alListener3f(AL10.AL_POSITION,
                    (float) listenerPos.x, (float) listenerPos.y, (float) listenerPos.z);
            AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{
                    (float) forward.x, (float) forward.y, (float) forward.z,
                    0f, 1f, 0f
            });
        }

        Iterator<Map.Entry<UUID, ActiveVoice>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            ActiveVoice voice = it.next().getValue();
            int state = AL10.alGetSourcei(voice.source(), AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING) {
                AL10.alDeleteSources(voice.source());
                AL10.alDeleteBuffers(voice.buffer());
                it.remove();
                continue;
            }
            if (voice.mode() == SoulVoicePayload.MODE_POSITIONAL) {
                Vec3d pos = botPositions.get(voice.botId());
                if (pos != null) {
                    AL10.alSource3f(voice.source(), AL10.AL_POSITION,
                            (float) pos.x, (float) pos.y, (float) pos.z);
                }
            }
            AL10.alSourcef(voice.source(), AL10.AL_GAIN, currentGain(voice.mode()));
        }
    }

    private static void stopVoiceOnAlThread(UUID botId) {
        ActiveVoice existing = ACTIVE.remove(botId);
        if (existing != null) {
            AL10.alSourceStop(existing.source());
            AL10.alDeleteSources(existing.source());
            AL10.alDeleteBuffers(existing.buffer());
        }
    }

    private static void stopAllOnAlThread() {
        for (ActiveVoice voice : ACTIVE.values()) {
            AL10.alSourceStop(voice.source());
            AL10.alDeleteSources(voice.source());
            AL10.alDeleteBuffers(voice.buffer());
        }
        ACTIVE.clear();
    }

    private static float currentGain(byte mode) {
        return mode == SoulVoicePayload.MODE_RADIO
                ? masterPlayersVolume * RADIO_GAIN
                : masterPlayersVolume;
    }

    /**
     * Opens our device + context using the {@code ALC_EXT_thread_local_context} extension so
     * this thread's "current context" is independent of whatever Minecraft's own sound thread
     * has current. Plain {@code alcMakeContextCurrent} is process-global and would race /
     * steal currency from the game's own audio — never call it here. If the extension isn't
     * present, playback is disabled for the rest of the session rather than risking that.
     */
    private static void ensureContext() {
        if (context != 0L || playbackDisabled) {
            return;
        }
        device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0L) {
            LOGGER.warn("Soul voice: failed to open OpenAL device");
            playbackDisabled = true;
            return;
        }
        ALCCapabilities alcCaps = ALC.createCapabilities(device);
        if (!alcCaps.ALC_EXT_thread_local_context) {
            LOGGER.warn("Soul voice: thread-local AL context unavailable — soul voice playback disabled to protect game audio");
            ALC10.alcCloseDevice(device);
            device = 0L;
            playbackDisabled = true;
            return;
        }
        long newContext = ALC10.alcCreateContext(device, (IntBuffer) null);
        if (newContext == 0L) {
            LOGGER.warn("Soul voice: failed to create OpenAL context");
            ALC10.alcCloseDevice(device);
            device = 0L;
            playbackDisabled = true;
            return;
        }
        if (!EXTThreadLocalContext.alcSetThreadContext(newContext)) {
            LOGGER.warn("Soul voice: failed to set thread-local AL context — soul voice playback disabled to protect game audio");
            ALC10.alcDestroyContext(newContext);
            ALC10.alcCloseDevice(device);
            device = 0L;
            playbackDisabled = true;
            return;
        }
        AL.createCapabilities(alcCaps);
        context = newContext;
    }

    private static void destroyContext() {
        if (context != 0L) {
            EXTThreadLocalContext.alcSetThreadContext(0L);
            ALC10.alcDestroyContext(context);
            context = 0L;
        }
        if (device != 0L) {
            ALC10.alcCloseDevice(device);
            device = 0L;
        }
    }

    // ---- task plumbing -------------------------------------------------------------------

    private static void alTask(Runnable task) {
        ensureAlThreadStarted();
        AL_TASKS.offer(task);
    }

    private static synchronized void ensureAlThreadStarted() {
        if (started) {
            return;
        }
        started = true;
        Thread thread = new Thread(SoulVoiceClientPlayer::runAlThread, "frens-soul-voice-al");
        thread.setDaemon(true);
        thread.start();
        alThread = thread;
    }

    private static void runAlThread() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                AL_TASKS.take().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.warn("Soul voice AL task failed", e);
            }
        }
    }
}
