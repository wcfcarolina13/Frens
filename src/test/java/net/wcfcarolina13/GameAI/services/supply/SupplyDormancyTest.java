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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Supplies Phase 3 routes every automatic chest withdrawal through {@code SupplyWithdrawals}:
 * {@code SupplyRequestService.request(...)} and {@code transferNow(...)} may be named only by the
 * service itself (their declarations) and by that facade. This scans {@code src/main/java}
 * (comments stripped) and fails on any call, method reference or static import of either in any
 * other file, and on any use of them inside the service beyond their declarations. Their private
 * bodies, {@code evaluateRequest} and {@code evaluateTransfer}, stay pinned: each is named exactly
 * twice in the service (its declaration and the first statement of its public entry point) and
 * never outside it, the facade included. A file outside {@code supply/} that names the service may
 * not hold a string literal naming any of the four methods, which is what reflection would need.
 * {@code SupplyCommands} only answers and revokes. The scan fails loudly when the source tree
 * cannot be found, so it can never pass by reading nothing.
 *
 * <p>(The class keeps its Phase 2 name, under which the plans and changelog cite it.)
 */
class SupplyDormancyTest {

    private static final String SERVICE = "SupplyRequestService.java";
    private static final String FACADE = "SupplyWithdrawals.java";
    private static final String COMMANDS = "SupplyCommands.java";
    private static final String SUPPLY_DIR = "net/wcfcarolina13/GameAI/services/supply/";
    /** The only files that may name request/transferNow. */
    private static final Set<String> ENTRY_POINT_FILES = Set.of(SUPPLY_DIR + SERVICE, SUPPLY_DIR + FACADE);
    private static final String ROUTED =
            "supplies Phase 3 routes automatic withdrawals through SupplyWithdrawals: ";

    /** A call or method reference to request/transferNow qualified by the service's name. */
    private static final Pattern QUALIFIED_USE =
            Pattern.compile("SupplyRequestService\\s*(?:\\.|::)\\s*(?:request|transferNow)\\b");
    private static final Pattern QUALIFIED_REQUEST_CALL = Pattern.compile("SupplyRequestService\\s*\\.\\s*request\\s*\\(");
    private static final Pattern QUALIFIED_TRANSFER_CALL =
            Pattern.compile("SupplyRequestService\\s*\\.\\s*transferNow\\s*\\(");
    /** A static import that would let either be called unqualified. */
    private static final Pattern STATIC_IMPORT =
            Pattern.compile("import\\s+static\\s+[\\w.]*SupplyRequestService\\s*\\.\\s*(?:\\*|request\\b|transferNow\\b)");
    /** transferNow is unique to the service; any mention elsewhere is a use. */
    private static final Pattern TRANSFER_NOW = Pattern.compile("\\btransferNow\\b");
    private static final Pattern BARE_REQUEST_CALL = Pattern.compile("(?<![\\w.])request\\s*\\(");
    private static final Pattern TRANSFER_NOW_CALL = Pattern.compile("\\btransferNow\\s*\\(");
    private static final Pattern SELF_REFERENCE = Pattern.compile("::\\s*(?:request|transferNow)\\b");

    /** Any mention of the private bodies: a call, a method reference or a name in a string. */
    private static final Pattern EVALUATE_REQUEST = Pattern.compile("\\bevaluateRequest\\b");
    private static final Pattern EVALUATE_TRANSFER = Pattern.compile("\\bevaluateTransfer\\b");
    private static final Pattern EVALUATE_ANY = Pattern.compile("\\bevaluate(?:Request|Transfer)\\b");
    /** The one permitted call of each body: the first statement of its public entry point. */
    private static final Pattern REQUEST_PINNED_CALL = Pattern.compile(
            "public static RequestOutcome request\\([^)]*\\)\\s*\\{\\s*RequestOutcome\\s+outcome\\s*=\\s*evaluateRequest\\s*\\(");
    private static final Pattern TRANSFER_PINNED_CALL = Pattern.compile(
            "public static TransferOutcome transferNow\\([^)]*\\)\\s*\\{\\s*TransferResult\\s+result\\s*=\\s*evaluateTransfer\\s*\\(");
    /** A string literal that is exactly one of the four method names, as reflection would pass it. */
    private static final Pattern REFLECTIVE_NAME =
            Pattern.compile("\"\\s*(?:request|transferNow|evaluateRequest|evaluateTransfer)\\s*\"");
    private static final Pattern NAMES_THE_SERVICE = Pattern.compile("\\bSupplyRequestService\\b");

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
        fail("src/main/java/net/wcfcarolina13 not found from " + dir + "; the entry-point scan cannot run");
        return null;
    }

    @Test
    void scanReadsTheRealTree() {
        assertTrue(sources.size() > 100, "suspiciously few sources scanned: " + sources.size());
        String service = sources.get(SUPPLY_DIR + SERVICE);
        assertNotNull(service, "SupplyRequestService.java not found where expected");
        assertNotNull(sources.get(SUPPLY_DIR + FACADE), "SupplyWithdrawals.java not found where expected");
        assertTrue(sources.containsKey("net/wcfcarolina13/Commands/" + COMMANDS), "SupplyCommands.java not found");
        // The entry points still exist under these names; renaming them must update this test.
        assertTrue(service.contains("public static RequestOutcome request("), "request( declaration moved or renamed");
        assertTrue(service.contains("public static TransferOutcome transferNow("),
                "transferNow( declaration moved or renamed");
    }

    @Test
    void onlyTheServiceAndTheFacadeNameRequestOrTransferNow() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (ENTRY_POINT_FILES.contains(e.getKey())) {
                continue;
            }
            String text = e.getValue();
            if (QUALIFIED_USE.matcher(text).find() || STATIC_IMPORT.matcher(text).find()
                    || TRANSFER_NOW.matcher(text).find() || EVALUATE_ANY.matcher(text).find()) {
                offenders.add(e.getKey());
            }
        }
        assertEquals(List.of(), offenders,
                ROUTED + "only it may call request(/transferNow(; a caller wants SupplyWithdrawals.withdraw(");
    }

    @Test
    void theFacadeCallsBothEntryPointsAndNeverTheirBodies() {
        String facade = sources.get(SUPPLY_DIR + FACADE);
        assertNotNull(facade);
        assertTrue(QUALIFIED_REQUEST_CALL.matcher(facade).find(), "the scan must see the facade's real request( call");
        assertTrue(QUALIFIED_TRANSFER_CALL.matcher(facade).find(),
                "the scan must see the facade's real transferNow( call");
        assertFalse(EVALUATE_ANY.matcher(facade).find(), "the facade goes through the entry points, not their bodies");
        assertFalse(STATIC_IMPORT.matcher(facade).find(), "the facade names the service on every call");
    }

    @Test
    void noFileOutsideSupplyNamesTheEntryPointsForReflection() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (e.getKey().startsWith(SUPPLY_DIR)) {
                continue;
            }
            String text = e.getValue();
            if (NAMES_THE_SERVICE.matcher(text).find() && REFLECTIVE_NAME.matcher(text).find()) {
                offenders.add(e.getKey());
            }
        }
        assertEquals(List.of(), offenders,
                "a file naming SupplyRequestService may not hold \"request\"/\"transferNow\"/\"evaluate…\" strings");
    }

    @Test
    void theServiceNeverCallsItsOwnEntryPoints() {
        String service = sources.get(SUPPLY_DIR + SERVICE);
        assertNotNull(service);
        assertEquals(1, count(BARE_REQUEST_CALL, service), "request( must appear only as its declaration");
        assertEquals(1, count(TRANSFER_NOW_CALL, service), "transferNow( must appear only as its declaration");
        assertFalse(SELF_REFERENCE.matcher(service).find(), "no method reference to request/transferNow");
    }

    @Test
    void thePrivateBodiesAreReachedOnlyThroughTheirEntryPoints() {
        String service = sources.get(SUPPLY_DIR + SERVICE);
        assertNotNull(service);
        assertTrue(service.contains("private static RequestOutcome evaluateRequest("),
                "evaluateRequest( declaration moved, renamed or made non-private");
        assertTrue(service.contains("private static TransferResult evaluateTransfer("),
                "evaluateTransfer( declaration moved, renamed or made non-private");
        assertEquals(2, count(EVALUATE_REQUEST, service),
                "evaluateRequest may be named only by its declaration and its one call in request(");
        assertEquals(2, count(EVALUATE_TRANSFER, service),
                "evaluateTransfer may be named only by its declaration and its one call in transferNow(");
        assertEquals(1, count(REQUEST_PINNED_CALL, service), "request( must open by calling evaluateRequest(");
        assertEquals(1, count(TRANSFER_PINNED_CALL, service), "transferNow( must open by calling evaluateTransfer(");
    }

    @Test
    void supplyCommandsOnlyAnswersAndRevokes() {
        String commands = sources.get("net/wcfcarolina13/Commands/" + COMMANDS);
        assertNotNull(commands);
        assertFalse(QUALIFIED_USE.matcher(commands).find());
        assertFalse(TRANSFER_NOW.matcher(commands).find());
        assertFalse(commands.contains("SupplyWithdrawals"), "commands never withdraw on a companion's behalf");
        assertTrue(commands.contains("SupplyRequestService.answer("), "the scan must see real command code");
        assertTrue(commands.contains("SupplyRequestService.revoke("), "the scan must see real command code");
        assertTrue(commands.contains("SupplyRequestService.revokeAll("), "the scan must see real command code");
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
        // The facade's policy names the service's status enums; those are not entry points.
        assertFalse(QUALIFIED_USE.matcher("import x.SupplyRequestService.RequestStatus; SupplyRequestService.TransferStatus s;").find());
        assertFalse(TRANSFER_NOW.matcher("TransferStatus.NO_ROOM; TransferOutcome o;").find());
        assertTrue(QUALIFIED_REQUEST_CALL.matcher("RequestOutcome a = SupplyRequestService.request(bot, p, s, 1, 1);").find());
        assertFalse(QUALIFIED_REQUEST_CALL.matcher("SupplyRequestService.requestCount()").find());
        assertTrue(QUALIFIED_TRANSFER_CALL.matcher("SupplyRequestService . transferNow (bot, p, fp, 1)").find());
        assertEquals(1, count(BARE_REQUEST_CALL, "static RequestOutcome request(ServerPlayerEntity bot) { evaluate(x); ledger.request(y); }"));
        assertEquals(0, count(BARE_REQUEST_CALL, "LOGGER.info(\"[supply] request bot={}\"); requestId(); fooRequest(1);"));

        assertTrue(EVALUATE_ANY.matcher("TransferResult r = evaluateTransfer(bot, pos, fp, 1);").find());
        assertTrue(EVALUATE_ANY.matcher("Function<?, ?> f = SupplyRequestService::evaluateRequest;").find());
        assertFalse(EVALUATE_ANY.matcher("evaluateRequests(); reevaluateTransfer();").find());
        assertTrue(REQUEST_PINNED_CALL.matcher("public static RequestOutcome request(ServerPlayerEntity bot,\n"
                + "        BlockPos chestPos, ItemStack sample, int qty, int need) {\n"
                + "    RequestOutcome outcome = evaluateRequest(bot, chestPos, sample, qty, need);").find());
        assertFalse(REQUEST_PINNED_CALL.matcher("public static RequestOutcome request(ServerPlayerEntity bot) {\n"
                + "    log(); RequestOutcome outcome = evaluateRequest(bot);").find());
        assertTrue(TRANSFER_PINNED_CALL.matcher("public static TransferOutcome transferNow(ServerPlayerEntity bot, int need) {"
                + " TransferResult result = evaluateTransfer(bot, need);").find());
        assertFalse(TRANSFER_PINNED_CALL.matcher("public static TransferOutcome transferNow(ServerPlayerEntity bot) {"
                + " log(); TransferResult result = evaluateTransfer(bot);").find());
        assertFalse(TRANSFER_PINNED_CALL.matcher("public static int transferNow(ServerPlayerEntity bot, int need) {"
                + " TransferResult result = evaluateTransfer(bot, need);").find(), "the Phase 2 int shape is gone");
        assertTrue(REFLECTIVE_NAME.matcher("SupplyRequestService.class.getDeclaredMethod(\"request\", A.class)").find());
        assertTrue(REFLECTIVE_NAME.matcher("m = c.getDeclaredMethod( \"evaluateTransfer\" );").find());
        assertFalse(REFLECTIVE_NAME.matcher("LOGGER.info(\"[supply] request bot={}\"); x(\"requests\");").find());
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
