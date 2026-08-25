package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detection + download service behind the soul LLM model manager screen — the Ollama
 * counterpart of {@link net.wcfcarolina13.GameAI.souls.voice.PiperInstaller}.
 *
 * <p>Talks only to the user's local Ollama daemon over its HTTP API (version, tags,
 * streaming pull). The mod cannot install Ollama itself — when the daemon is unreachable
 * the screen says so plainly and points at ollama.com. Model tags and sizes below were
 * verified against registry.ollama.ai (2026-08-25), not inferred.
 */
public final class OllamaModelInstaller {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A model the manager offers, with registry-verified size and an honest RAM guide. */
    public record KnownModel(String tag, String label, String description,
                             double downloadGb, double recommendedRamGb) {
    }

    public static final List<KnownModel> KNOWN_MODELS = List.of(
            new KnownModel("llama3.1:8b", "Llama 3.1 8B",
                    "Current default — best replies, slowest, heaviest.", 4.6, 12),
            new KnownModel("llama3.2:3b", "Llama 3.2 3B",
                    "Smaller — noticeably faster, decent replies.", 1.9, 6),
            new KnownModel("llama3.2:1b", "Llama 3.2 1B",
                    "Tiny — fastest, simplest replies.", 1.3, 4));

    public record InstalledModel(String tag, long sizeBytes) {
    }

    /** Everything the screen shows before the user clicks anything. */
    public record Status(boolean reachable, String version, String baseUrl,
                          List<InstalledModel> installed, String currentModel,
                          long totalRamBytes, long freeDiskBytes) {
        public boolean isInstalled(String tag) {
            return installed.stream().anyMatch(m -> m.tag().equals(tag));
        }
    }

    public interface Progress {
        void update(String stage, long bytesDone, long bytesTotal);
    }

    private OllamaModelInstaller() {
    }

    // ── Background job (screen-independent, survives menu close/reopen) ──────

    private static final java.util.concurrent.atomic.AtomicReference<InstallJob> ACTIVE_JOB =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** The running (or finished-but-unconsumed) pull job, or null. */
    public static InstallJob activeJob() {
        return ACTIVE_JOB.get();
    }

    /** Clears a finished job so a new one can start; no-op while one is still running. */
    public static void clearFinishedJob() {
        InstallJob job = ACTIVE_JOB.get();
        if (job != null && job.finished()) {
            ACTIVE_JOB.compareAndSet(job, null);
        }
    }

    /**
     * Pulls {@code tag} then selects it, on a service-owned daemon thread. Returns false
     * if a job is already active — callers attach to {@link #activeJob()} instead, so
     * closing and reopening the screen mid-download can never start a duplicate pull.
     */
    public static boolean pullAsync(String tag) {
        InstallJob job = new InstallJob(tag);
        if (!ACTIVE_JOB.compareAndSet(null, job)) {
            return false;
        }
        Thread t = new Thread(() -> {
            try {
                pull(tag, job::progress);
                select(tag);
                job.finishOk();
            } catch (Throwable ex) {
                job.finishFailed(String.valueOf(ex.getMessage()));
            }
        }, "frens-ollama-pull");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static String baseUrl() {
        ManualConfig cfg = Frens.CONFIG;
        String url = cfg != null ? cfg.getOllamaBaseUrl() : null;
        return (url == null || url.isBlank()) ? "http://127.0.0.1:11434" : url.trim();
    }

    // ── Detection ────────────────────────────────────────────────────────────

    public static Status detect() {
        String base = baseUrl();
        boolean reachable = false;
        String version = "";
        List<InstalledModel> installed = new ArrayList<>();
        try {
            JsonNode v = getJson(base + "/api/version", 4_000);
            version = v.path("version").asText("");
            reachable = true;
            JsonNode tags = getJson(base + "/api/tags", 6_000);
            for (JsonNode m : tags.path("models")) {
                installed.add(new InstalledModel(m.path("name").asText(""), m.path("size").asLong(0)));
            }
        } catch (IOException e) {
            LOGGER.info("[souls] ollama not reachable at {}: {}", base, e.toString());
        }

        long totalRam = -1L;
        try {
            totalRam = ((com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        } catch (Throwable ignored) {
            // non-HotSpot JVM — leave unknown
        }
        long freeDisk = -1L;
        try {
            freeDisk = Files.getFileStore(Path.of(System.getProperty("user.home", "/")))
                    .getUsableSpace();
        } catch (IOException ignored) {
        }

        ManualConfig cfg = Frens.CONFIG;
        String current = cfg != null ? cfg.getSoulModel() : "";
        return new Status(reachable, version, base, installed, current == null ? "" : current,
                totalRam, freeDisk);
    }

    // ── Pull (streaming) ─────────────────────────────────────────────────────

    /**
     * Streams {@code POST /api/pull} for {@code tag}, reporting layer progress. Runs on
     * the caller's worker thread; Ollama resumes partial pulls itself, so a failure here
     * is safely retryable. Throws with a user-showable message on failure.
     */
    public static void pull(String tag, Progress progress) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl() + "/api/pull")
                .toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(120_000);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(JSON.writeValueAsString(
                            java.util.Map.of("model", tag, "stream", true))
                    .getBytes(StandardCharsets.UTF_8));
        }
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON.readTree(line);
                String err = node.path("error").asText("");
                if (!err.isEmpty()) {
                    throw new IOException("Ollama: " + err);
                }
                String status = node.path("status").asText("");
                long total = node.path("total").asLong(0);
                long completed = node.path("completed").asLong(0);
                progress.update(status.isEmpty() ? "Downloading…" : status, completed, total);
                if ("success".equals(status)) {
                    return;
                }
            }
        } finally {
            conn.disconnect();
        }
        throw new IOException("Pull stream ended without success — is Ollama still running?");
    }

    /** Sets {@code tag} as the soul model and hot-reloads the runtime. */
    public static void select(String tag) {
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            return;
        }
        cfg.setSoulModel(tag);
        cfg.save();
        SoulRuntime.current().ifPresent(rt -> rt.reloadSettings(cfg).exceptionally(ex -> {
            LOGGER.warn("[souls] reloadSettings failed after model switch: {}", ex.toString());
            return null;
        }));
        LOGGER.info("[souls] soul model set to {}", tag);
    }

    private static JsonNode getJson(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        try {
            return JSON.readTree(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }
}
