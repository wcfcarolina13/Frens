package net.wcfcarolina13.GameAI.services.supply;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Supplies Phase 2 is dormant: {@code SupplyRequestService.request(...)} and
 * {@code transferNow(...)} must have no production caller until Phase 3 has closed the existing
 * automatic chest withdrawals. This scans {@code src/main/java} (comments stripped) and fails on
 * any call, method reference or static import of either outside {@code SupplyRequestService}
 * itself, and on any use of them inside it beyond their declarations. The scan fails loudly when
 * the source tree cannot be found, so it can never pass by reading nothing.
 */
class SupplyDormancyTest {

    private static final String SERVICE = "SupplyRequestService.java";
    private static final String COMMANDS = "SupplyCommands.java";
    private static final String SUPPLY_DIR = "net/wcfcarolina13/GameAI/services/supply/";

    /** A call or method reference to request/transferNow qualified by the service's name. */
    private static final Pattern QUALIFIED_USE =
            Pattern.compile("SupplyRequestService\\s*(?:\\.|::)\\s*(?:request|transferNow)\\b");
    /** A static import that would let either be called unqualified. */
    private static final Pattern STATIC_IMPORT =
            Pattern.compile("import\\s+static\\s+[\\w.]*SupplyRequestService\\s*\\.\\s*(?:\\*|request\\b|transferNow\\b)");
    /** transferNow is unique to the service; any mention elsewhere is a use. */
    private static final Pattern TRANSFER_NOW = Pattern.compile("\\btransferNow\\b");
    private static final Pattern BARE_REQUEST_CALL = Pattern.compile("(?<![\\w.])request\\s*\\(");
    private static final Pattern TRANSFER_NOW_CALL = Pattern.compile("\\btransferNow\\s*\\(");
    private static final Pattern SELF_REFERENCE = Pattern.compile("::\\s*(?:request|transferNow)\\b");

    private static Map<String, String> sources;

    @BeforeAll
    static void readSourceTree() {
        Path root = findMainJavaRoot();
        Map<String, String> read = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                read.put(relative, stripComments(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + root, e);
        }
        sources = read;
    }

    /** Walks up from the working directory to the project's {@code src/main/java}. */
    private static Path findMainJavaRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            Path candidate = d.resolve("src/main/java");
            if (Files.isDirectory(candidate.resolve("net/wcfcarolina13"))) {
                return candidate;
            }
        }
        fail("src/main/java/net/wcfcarolina13 not found from " + dir + "; the dormancy scan cannot run");
        return null;
    }

    @Test
    void scanReadsTheRealTree() {
        assertTrue(sources.size() > 100, "suspiciously few sources scanned: " + sources.size());
        String service = sources.get(SUPPLY_DIR + SERVICE);
        assertNotNull(service, "SupplyRequestService.java not found where expected");
        assertTrue(sources.containsKey("net/wcfcarolina13/Commands/" + COMMANDS), "SupplyCommands.java not found");
        // The dormant entry points still exist under these names; renaming them must update this test.
        assertTrue(service.contains("public static RequestOutcome request("), "request( declaration moved or renamed");
        assertTrue(service.contains("public static int transferNow("), "transferNow( declaration moved or renamed");
    }

    @Test
    void noFileCallsRequestOrTransferNowFromOutsideTheService() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (e.getKey().equals(SUPPLY_DIR + SERVICE)) {
                continue;
            }
            String text = e.getValue();
            if (QUALIFIED_USE.matcher(text).find() || STATIC_IMPORT.matcher(text).find()
                    || TRANSFER_NOW.matcher(text).find()) {
                offenders.add(e.getKey());
            }
        }
        assertEquals(List.of(), offenders,
                "supplies Phase 2 is dormant: request(/transferNow( must have no caller until Phase 3");
    }

    @Test
    void theServiceNeverCallsItsOwnDormantEntryPoints() {
        String service = sources.get(SUPPLY_DIR + SERVICE);
        assertNotNull(service);
        assertEquals(1, count(BARE_REQUEST_CALL, service), "request( must appear only as its declaration");
        assertEquals(1, count(TRANSFER_NOW_CALL, service), "transferNow( must appear only as its declaration");
        assertFalse(SELF_REFERENCE.matcher(service).find(), "no method reference to request/transferNow");
    }

    @Test
    void supplyCommandsOnlyAnswersAndRevokes() {
        String commands = sources.get("net/wcfcarolina13/Commands/" + COMMANDS);
        assertNotNull(commands);
        assertFalse(QUALIFIED_USE.matcher(commands).find());
        assertFalse(TRANSFER_NOW.matcher(commands).find());
        assertTrue(commands.contains("SupplyRequestService.answer("), "the scan must see real command code");
        assertTrue(commands.contains("SupplyRequestService.revoke("), "the scan must see real command code");
    }

    @Test
    void patternsCatchTheCallShapesTheyGuard() {
        assertTrue(QUALIFIED_USE.matcher("SupplyRequestService.request(bot, pos, stack, 4, 4);").find());
        assertTrue(QUALIFIED_USE.matcher("x = net.wcfcarolina13.GameAI.services.supply.SupplyRequestService . transferNow (b, p, fp, 1);").find());
        assertTrue(QUALIFIED_USE.matcher("run(SupplyRequestService::request);").find());
        assertTrue(STATIC_IMPORT.matcher("import static net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.*;").find());
        assertTrue(STATIC_IMPORT.matcher("import static net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.request;").find());
        assertTrue(TRANSFER_NOW.matcher("service.transferNow(bot, pos, fp, 2)").find());
        assertFalse(QUALIFIED_USE.matcher("SupplyRequestService.register(); SupplyRequestService.answer(p, id, c);").find());
        assertFalse(QUALIFIED_USE.matcher("SupplyRequestService.requestCount()").find());
        assertEquals(1, count(BARE_REQUEST_CALL, "static RequestOutcome request(ServerPlayerEntity bot) { evaluate(x); ledger.request(y); }"));
        assertEquals(0, count(BARE_REQUEST_CALL, "LOGGER.info(\"[supply] request bot={}\"); requestId(); fooRequest(1);"));
    }

    @Test
    void commentsAreStrippedBeforeScanning() {
        assertFalse(stripComments("// SupplyRequestService.request(a)\n/* transferNow( */ int x;").contains("request("));
        assertTrue(stripComments("a(); // note\nb();").contains("b();"));
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Removes block and line comments; string literals are left alone (none of the guarded shapes are URLs). */
    private static String stripComments(String source) {
        String noBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlocks.replaceAll("(?m)(?<![:\"])//[^\\n]*", " ");
    }
}
