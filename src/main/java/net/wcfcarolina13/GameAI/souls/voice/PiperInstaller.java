package net.wcfcarolina13.GameAI.souls.voice;

import net.fabricmc.loader.api.FabricLoader;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Detection + download + verify + smoke-test service behind the Piper installer screen.
 *
 * <p>Everything is pinned: release tag, per-platform asset name/size/sha256, and the voice
 * files — nothing is inferred at runtime, and every downloaded byte is hash-verified before
 * it is trusted. The prebuilt C++ piper (release {@value #RELEASE_TAG}) is the target
 * because its CLI contract ({@code --model … --output_dir}, text on stdin, rendered WAV
 * path on stdout) is exactly what {@link PiperVoiceEngine} speaks. A pre-installed piper
 * (e.g. the pipx Python distribution) may NOT honor that contract, so "use existing"
 * candidates are only accepted after the same synth smoke test a fresh download gets.
 *
 * <p>Single-player oriented: the binary lands under the game directory and the config is
 * updated in-process. (Dedicated servers would need the binary server-side — out of scope
 * while the config sync path is stubbed.)
 */
public final class PiperInstaller {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    public static final String RELEASE_TAG = "2023.11.14-2";
    private static final String RELEASE_BASE =
            "https://github.com/rhasspy/piper/releases/download/" + RELEASE_TAG + "/";
    private static final String VOICE_BASE =
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/";
    public static final String VOICE_NAME = "en_US-lessac-medium";

    /** One pinned downloadable file: url, exact size, sha256. */
    public record PinnedFile(String url, String fileName, long size, String sha256) {
        public String host() {
            return URI.create(url).getHost();
        }
    }

    // sha256 pinned 2026-08-25 from one-time verified downloads of the release assets.
    private static final PinnedFile MACOS_AARCH64 = new PinnedFile(
            RELEASE_BASE + "piper_macos_aarch64.tar.gz", "piper_macos_aarch64.tar.gz",
            19_146_957L, "6b1eb03b3735946cb35216e063e7eebcc33a6bbf5dd96ec0217959bf1cdcb0cc");
    private static final PinnedFile MACOS_X64 = new PinnedFile(
            RELEASE_BASE + "piper_macos_x64.tar.gz", "piper_macos_x64.tar.gz",
            19_146_927L, "ced85c0a3df13945b1e623b878a48fdc2854d5c485b4b67f62857cf551deaf8b");
    private static final PinnedFile LINUX_X86_64 = new PinnedFile(
            RELEASE_BASE + "piper_linux_x86_64.tar.gz", "piper_linux_x86_64.tar.gz",
            26_460_462L, "a50cb45f355b7af1f6d758c1b360717877ba0a398cc8cbe6d2a7a3a26e225992");
    private static final PinnedFile WINDOWS_AMD64 = new PinnedFile(
            RELEASE_BASE + "piper_windows_amd64.zip", "piper_windows_amd64.zip",
            22_477_236L, "f3c58906402b24f3a96d92145f58acba6d86c9b5db896d207f78dc80811efcea");

    /**
     * The macOS piper archives above are missing their runtime dylibs upstream
     * (libespeak-ng.1, libpiper_phonemize.1, libonnxruntime.1.14.1 — only the .dSYM debug
     * bundle ships), and the binary has no LC_RPATH, so it can't start. All three dylibs
     * ship in the piper-phonemize release for the same toolchain; on macOS the installer
     * downloads it too and copies the dylibs next to the binary, and
     * {@link PiperVoiceEngine} spawns with DYLD_LIBRARY_PATH pointing there.
     * Windows (.dll) and Linux (.so) piper archives are complete — verified by listing.
     */
    private static final String PHONEMIZE_BASE =
            "https://github.com/rhasspy/piper-phonemize/releases/download/2023.11.14-4/";
    private static final PinnedFile PHONEMIZE_MACOS_AARCH64 = new PinnedFile(
            PHONEMIZE_BASE + "piper-phonemize_macos_aarch64.tar.gz", "piper-phonemize_macos_aarch64.tar.gz",
            26_641_933L, "78a9c28b3c94baf6e9526b2e386ce547909abaec4f31aadd7e16b01fbfe5f322");
    private static final PinnedFile PHONEMIZE_MACOS_X64 = new PinnedFile(
            PHONEMIZE_BASE + "piper-phonemize_macos_x64.tar.gz", "piper-phonemize_macos_x64.tar.gz",
            26_641_959L, "9ec6e300c0d012a663758bc45a097b47ee759761a3b91c7742de042af789d84b");
    private static final String[] MACOS_DYLIBS = {
            "libespeak-ng.1.dylib", "libpiper_phonemize.1.dylib", "libonnxruntime.1.14.1.dylib"};

    private static final PinnedFile VOICE_ONNX = new PinnedFile(
            VOICE_BASE + VOICE_NAME + ".onnx", VOICE_NAME + ".onnx",
            63_201_294L, "5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f");
    private static final PinnedFile VOICE_JSON = new PinnedFile(
            VOICE_BASE + VOICE_NAME + ".onnx.json", VOICE_NAME + ".onnx.json",
            4_885L, "efe19c417bed055f2d69908248c6ba650fa135bc868b0e6abb3da181dab690a0");

    /** A piper binary found on this machine before installing anything. */
    public record ExistingBinary(Path path, String origin) {
    }

    /** Everything the installer screen shows the user before they click anything. */
    public record Plan(boolean platformSupported, String platformName, PinnedFile asset,
                       Path installDir, Path binaryPath, Path voiceOnnxPath,
                       long downloadBytes, long freeDiskBytes,
                       List<ExistingBinary> existingBinaries, boolean voiceAlreadyPresent,
                       PinnedFile macosLibsAsset) {
    }

    /** Progress callback; safe to publish to volatile fields for the render thread. */
    public interface Progress {
        void update(String stage, long bytesDone, long bytesTotal);
    }

    private PiperInstaller() {
    }

    // ── Detection ────────────────────────────────────────────────────────────

    public static Plan detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        PinnedFile asset;
        PinnedFile macosLibs = null;
        String platformName;
        if (os.contains("mac")) {
            asset = arm ? MACOS_AARCH64 : MACOS_X64;
            macosLibs = arm ? PHONEMIZE_MACOS_AARCH64 : PHONEMIZE_MACOS_X64;
            platformName = "macOS " + (arm ? "(Apple Silicon)" : "(Intel)");
        } else if (os.contains("win")) {
            asset = arm ? null : WINDOWS_AMD64;
            platformName = "Windows" + (arm ? " (ARM — unsupported)" : " (x64)");
        } else if (os.contains("linux")) {
            asset = arm ? null : LINUX_X86_64;
            platformName = "Linux" + (arm ? " (ARM — unsupported)" : " (x64)");
        } else {
            asset = null;
            platformName = os + "/" + arch;
        }

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path installDir = gameDir.resolve("config").resolve("frens").resolve("piper");
        Path binaryPath = installDir.resolve("piper")
                .resolve(os.contains("win") ? "piper.exe" : "piper");
        Path voiceDir = installDir.resolve("voices");
        Path voiceOnnx = voiceDir.resolve(VOICE_ONNX.fileName());

        long free;
        try {
            Files.createDirectories(installDir);
            free = Files.getFileStore(installDir).getUsableSpace();
        } catch (IOException e) {
            free = -1L;
        }

        long downloadBytes = (asset == null ? 0 : asset.size())
                + (macosLibs == null ? 0 : macosLibs.size())
                + (Files.exists(voiceOnnx) ? 0 : VOICE_ONNX.size() + VOICE_JSON.size());

        List<ExistingBinary> existing = findExistingBinaries(binaryPath, os.contains("win"));
        boolean voicePresent = Files.exists(voiceOnnx);

        return new Plan(asset != null, platformName, asset, installDir, binaryPath, voiceOnnx,
                downloadBytes, free, existing, voicePresent, macosLibs);
    }

    private static List<ExistingBinary> findExistingBinaries(Path managedBinary, boolean windows) {
        List<ExistingBinary> found = new ArrayList<>();
        // The configured path first, then this installer's own managed location, then
        // common install spots and the PATH.
        ManualConfig cfg = Frens.CONFIG;
        if (cfg != null && !cfg.getSoulVoicePiperBinary().isBlank()) {
            addIfExecutable(found, Path.of(cfg.getSoulVoicePiperBinary()), "configured in settings.json5");
        }
        addIfExecutable(found, managedBinary, "previous install by this mod");
        String home = System.getProperty("user.home", "");
        if (!windows) {
            addIfExecutable(found, Path.of(home, ".local", "bin", "piper"), "~/.local/bin (pipx)");
            addIfExecutable(found, Path.of("/opt/homebrew/bin/piper"), "Homebrew");
            addIfExecutable(found, Path.of("/usr/local/bin/piper"), "/usr/local/bin");
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String binName = windows ? "piper.exe" : "piper";
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                addIfExecutable(found, Path.of(dir, binName), "on PATH");
            }
        }
        return found;
    }

    private static void addIfExecutable(List<ExistingBinary> out, Path candidate, String origin) {
        try {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)
                    && out.stream().noneMatch(e -> e.path().equals(candidate))) {
                out.add(new ExistingBinary(candidate, origin));
            }
        } catch (RuntimeException ignored) {
            // unreadable path — skip
        }
    }

    // ── Install ──────────────────────────────────────────────────────────────

    /**
     * Full flow on the CALLER's worker thread (never the client/render thread):
     * download+verify+extract the binary (skipped when {@code existingBinary} is given),
     * download+verify the voice if missing, smoke-test actual synthesis, then write config
     * and hot-reload the soul runtime. Throws with a user-showable message on any failure —
     * nothing is half-installed into the live config.
     */
    public static void install(Plan plan, Path existingBinary, Progress progress) throws Exception {
        Path binary;
        if (existingBinary != null) {
            binary = existingBinary;
        } else {
            if (!plan.platformSupported()) {
                throw new IOException("No prebuilt Piper for this platform (" + plan.platformName() + ")");
            }
            Files.createDirectories(plan.installDir());
            Path archive = plan.installDir().resolve(plan.asset().fileName());
            download(plan.asset(), archive, progress);
            progress.update("Extracting…", 0, 0);
            extract(archive, plan.installDir());
            Files.deleteIfExists(archive);
            binary = plan.binaryPath();
            if (!Files.isRegularFile(binary)) {
                throw new IOException("Archive extracted but binary not found at " + binary);
            }
            binary.toFile().setExecutable(true);

            // macOS: the piper archive ships without its runtime dylibs (upstream packaging
            // defect) — fetch them from the pinned piper-phonemize release and place them
            // beside the binary. PiperVoiceEngine spawns with DYLD_LIBRARY_PATH there.
            if (plan.macosLibsAsset() != null) {
                Path libsArchive = plan.installDir().resolve(plan.macosLibsAsset().fileName());
                download(plan.macosLibsAsset(), libsArchive, progress);
                progress.update("Extracting libraries…", 0, 0);
                Path libsDir = plan.installDir().resolve("phonemize-extract");
                Files.createDirectories(libsDir);
                extract(libsArchive, libsDir);
                Files.deleteIfExists(libsArchive);
                Path libSrc = libsDir.resolve("piper-phonemize").resolve("lib");
                for (String dylib : MACOS_DYLIBS) {
                    Path src = libSrc.resolve(dylib);
                    if (!Files.isRegularFile(src)) {
                        throw new IOException("Expected library missing from archive: " + dylib);
                    }
                    Files.copy(src, binary.getParent().resolve(dylib),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                deleteRecursively(libsDir);
            }
        }

        if (!Files.exists(plan.voiceOnnxPath())) {
            Files.createDirectories(plan.voiceOnnxPath().getParent());
            download(VOICE_ONNX, plan.voiceOnnxPath(), progress);
            download(VOICE_JSON, plan.voiceOnnxPath().resolveSibling(VOICE_JSON.fileName()), progress);
        }

        progress.update("Testing synthesis…", 0, 0);
        smokeTest(binary, plan.voiceOnnxPath());

        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            throw new IllegalStateException("Config not loaded");
        }
        cfg.setSoulVoiceEngine(SoulVoiceSettings.ENGINE_PIPER);
        cfg.setSoulVoicePiperBinary(binary.toAbsolutePath().toString());
        cfg.setSoulVoiceModel(plan.voiceOnnxPath().toAbsolutePath().toString());
        cfg.setSoulVoiceEnabled(true);
        cfg.save();
        net.wcfcarolina13.GameAI.souls.SoulRuntime.current()
                .ifPresent(rt -> rt.reloadSettings(cfg).exceptionally(ex -> {
                    LOGGER.warn("[souls] reloadSettings failed after piper install: {}", ex.toString());
                    return null;
                }));
        progress.update("Done", 0, 0);
        LOGGER.info("[souls] piper installed: binary={} voice={}", binary, plan.voiceOnnxPath());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of the temp extraction dir
                }
            });
        }
    }

    private static void download(PinnedFile file, Path dest, Progress progress) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(file.url()).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "frens-mod-piper-installer");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        long done = 0;
        try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                progress.update("Downloading " + file.fileName(), done, file.size());
            }
        } finally {
            conn.disconnect();
        }
        if (done != file.size()) {
            Files.deleteIfExists(tmp);
            throw new IOException(file.fileName() + ": size mismatch (" + done + " != " + file.size() + ")");
        }
        String hex = HexFormat.of().formatHex(sha.digest());
        if (!hex.equalsIgnoreCase(file.sha256())) {
            Files.deleteIfExists(tmp);
            throw new IOException(file.fileName() + ": checksum mismatch — download rejected");
        }
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void extract(Path archive, Path destDir) throws IOException, InterruptedException {
        String name = archive.getFileName().toString();
        if (name.endsWith(".zip")) {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path out = destDir.resolve(entry.getName()).normalize();
                    if (!out.startsWith(destDir)) {
                        throw new IOException("Zip entry escapes destination: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(zip, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } else {
            // .tar.gz — macOS and Linux always ship a system tar.
            Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", destDir.toString())
                    .redirectErrorStream(true).start();
            if (!tar.waitFor(120, TimeUnit.SECONDS) || tar.exitValue() != 0) {
                tar.destroyForcibly();
                throw new IOException("tar extraction failed");
            }
        }
    }

    /**
     * Proves the binary honors {@link PiperVoiceEngine}'s CLI contract by actually
     * synthesizing a line: {@code --model … --output_dir}, one text line on stdin, a
     * rendered WAV path on stdout. A pipx/Python piper that doesn't speak this contract
     * fails here instead of failing silently in-game later.
     */
    private static void smokeTest(Path binary, Path voice) throws IOException, InterruptedException {
        Path tmpDir = Files.createTempDirectory("frens-piper-smoke");
        ProcessBuilder pb = new ProcessBuilder(
                PiperVoiceEngine.command(binary.toString(), voice.toString(), tmpDir.toString()))
                .redirectErrorStream(false);
        PiperVoiceEngine.applyLibraryPathEnv(pb, binary.toString());
        Process proc = pb.start();
        // Drain stderr so the child can't block on a full pipe — but keep the tail so a
        // failure message can show WHY (e.g. a dyld library-not-loaded line).
        StringBuilder stderrTail = new StringBuilder();
        try {
            proc.getOutputStream().write("Piper installed successfully.\n".getBytes(StandardCharsets.UTF_8));
            proc.getOutputStream().flush();
            Thread drain = new Thread(() -> {
                try (java.io.BufferedReader err = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String errLine;
                    while ((errLine = err.readLine()) != null) {
                        synchronized (stderrTail) {
                            stderrTail.append(errLine).append(' ');
                            if (stderrTail.length() > 400) {
                                stderrTail.delete(0, stderrTail.length() - 400);
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "frens-piper-smoke-stderr");
            drain.setDaemon(true);
            drain.start();

            StringBuilder line = new StringBuilder();
            long deadline = System.currentTimeMillis() + 30_000;
            InputStream out = proc.getInputStream();
            while (System.currentTimeMillis() < deadline) {
                if (out.available() > 0) {
                    int c = out.read();
                    if (c == -1 || c == '\n') {
                        break;
                    }
                    line.append((char) c);
                } else if (!proc.isAlive() && out.available() == 0) {
                    throw new IOException("Piper exited during smoke test"
                            + errDetail(stderrTail));
                } else {
                    Thread.sleep(50);
                }
            }
            Path wav = Path.of(line.toString().trim());
            if (line.isEmpty() || !Files.isRegularFile(wav) || Files.size(wav) <= 44) {
                throw new IOException("Piper smoke test produced no audio — binary rejected "
                        + "(a Python 'piper' from pip/pipx may not support this CLI; use Download instead)"
                        + errDetail(stderrTail));
            }
            Files.deleteIfExists(wav);
        } finally {
            proc.destroyForcibly();
        }
    }

    private static String errDetail(StringBuilder stderrTail) {
        synchronized (stderrTail) {
            String s = stderrTail.toString().trim();
            return s.isEmpty() ? "" : " — " + s;
        }
    }
}
