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
import java.util.TreeSet;
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
 * never outside it, the facade included.
 *
 * <p>The ways around that are closed too: no file but the service may hold a string literal naming
 * any of the four methods while naming the service (reflection); nothing anywhere may hold a field,
 * variable, parameter, return type, type argument, cast or array of type {@code SupplyRequestService}
 * (a static called through an instance, {@code svc.request(...)}, would escape the qualified-name
 * scan); the facade's public static methods are exactly {@code withdraw} and
 * {@code grantableEstimate}, so no pass-through can be added beside them; and
 * {@code SupplyServerHop}, the shared hop the sites use, never names the service. {@code SupplyCommands}
 * only answers and revokes. The scan fails loudly when the source tree cannot be found, so it can
 * never pass by reading nothing.
 */
class SupplyEntryPointTest {

    private static final String SERVICE = "SupplyRequestService.java";
    private static final String FACADE = "SupplyWithdrawals.java";
    private static final String HOP = "SupplyServerHop.java";
    private static final String COMMANDS = "SupplyCommands.java";
    private static final String SUPPLY_DIR = "net/wcfcarolina13/GameAI/services/supply/";
    /** The only files that may name request/transferNow. */
    private static final Set<String> ENTRY_POINT_FILES = Set.of(SUPPLY_DIR + SERVICE, SUPPLY_DIR + FACADE);
    /** The facade's whole public static surface. */
    private static final Set<String> FACADE_PUBLIC_STATICS = Set.of("withdraw", "grantableEstimate");
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

    /**
     * {@code SupplyRequestService} used as a type: a field, local, parameter, for-each variable or
     * method return type; a type argument; a cast; an array; a constructor call.
     */
    private static final Pattern SERVICE_TYPED = Pattern.compile(
            "\\bSupplyRequestService\\s+[A-Za-z_$][\\w$]*\\s*[=;,:)(]"
                    + "|[<,]\\s*(?:\\?\\s*(?:extends|super)\\s+)?SupplyRequestService\\s*[>,\\[]"
                    + "|\\(\\s*SupplyRequestService\\s*\\)"
                    + "|\\bSupplyRequestService\\s*\\[\\s*\\]"
                    + "|\\bnew\\s+SupplyRequestService\\s*\\(");
    /**
     * A {@code public static} method declaration in member text: the name right before its
     * parameter list, with no statement, block, assignment or parameter list in between.
     */
    private static final Pattern PUBLIC_STATIC_MEMBER =
            Pattern.compile("\\bpublic\\s+static\\b([^;{}=()]*?)\\b([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern TYPE_KEYWORD = Pattern.compile("\\b(?:class|record|enum|interface)\\b");

    /** The shared hop's signature, which the sites call. */
    private static final String HOP_CALL_SIGNATURE =
            "public static <T> T call(MinecraftServer server, Supplier<T> task, long timeoutMs, T fallback)";

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
        assertNotNull(sources.get(SUPPLY_DIR + HOP), "SupplyServerHop.java not found where expected");
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
    void theFacadesPublicStaticsAreExactlyWithdrawAndTheEstimate() {
        String facade = sources.get(SUPPLY_DIR + FACADE);
        assertNotNull(facade);
        assertEquals(FACADE_PUBLIC_STATICS, publicStaticMethods(facade),
                ROUTED + "a public static beside withdraw/grantableEstimate could pass request(/transferNow( through");
    }

    @Test
    void noFileButTheServiceNamesTheEntryPointsForReflection() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (e.getKey().equals(SUPPLY_DIR + SERVICE)) {
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
    void nothingHoldsTheServiceAsAValue() {
        List<String> offenders = new ArrayList<>();
        int naming = 0;
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (!NAMES_THE_SERVICE.matcher(e.getValue()).find()) {
                continue; // a file that never names the type cannot hold a value of it
            }
            naming++;
            Matcher m = SERVICE_TYPED.matcher(stripLiterals(e.getValue()));
            if (m.find()) {
                offenders.add(e.getKey() + ": " + m.group().trim());
            }
        }
        assertTrue(naming >= 4, "the scan must see the service, the facade, its policy and the commands: " + naming);
        assertEquals(List.of(), offenders,
                ROUTED + "SupplyRequestService is static-only; a value of its type would let svc.request( escape the scan");
    }

    @Test
    void theSharedHopNeverNamesTheService() {
        String hop = sources.get(SUPPLY_DIR + HOP);
        assertNotNull(hop);
        assertFalse(NAMES_THE_SERVICE.matcher(hop).find(), "SupplyServerHop is a plain hop; it never reaches the service");
        assertTrue(hop.contains(HOP_CALL_SIGNATURE), "the sites call " + HOP_CALL_SIGNATURE);
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
    void theServiceTypedPatternCatchesEveryWayToHoldAValue() {
        for (String shape : List.of(
                "private static SupplyRequestService svc;",
                "SupplyRequestService s = null; s.request(bot, pos, st, 1, 1);",
                "void f(SupplyRequestService svc) {",
                "void f(int a, SupplyRequestService svc, int b) {",
                "void f(final SupplyRequestService\n        svc)",
                "for (SupplyRequestService s : all) {",
                "static SupplyRequestService instance() {",
                "List<SupplyRequestService> all;",
                "Map<String, ? extends SupplyRequestService> m;",
                "Map<SupplyRequestService, Integer> m;",
                "var s = (SupplyRequestService) null;",
                "SupplyRequestService[] arr;",
                "Object o = new SupplyRequestService();")) {
            assertTrue(SERVICE_TYPED.matcher(shape).find(), shape);
        }
        for (String fine : List.of(
                "SupplyRequestService.request(bot, p, s, 1, 1);",
                "SupplyRequestService.RequestStatus status = x;",
                "Map<SupplyRequestService.RequestStatus, Next> m;",
                "import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService;",
                "public final class SupplyRequestService {",
                "private SupplyRequestService() {",
                "Predicate<RequestFingerprint> p = SupplyRequestService::isPermitted;",
                "if (!SupplyRequestService.isRunning()) { return; }")) {
            assertFalse(SERVICE_TYPED.matcher(fine).find(), fine);
        }
        assertFalse(SERVICE_TYPED.matcher(stripLiterals("LOGGER.info(\"SupplyRequestService x;\");")).find(),
                "string literals are not code");
    }

    @Test
    void publicStaticsAreReadFromTheTopLevelClassBodyOnly() {
        String source = stripComments("package p;\n"
                + "import static java.util.Objects.requireNonNull;\n"
                + "public final class F {\n"
                + "    public static final long LIMIT = Math.max(1L, 2L);\n"
                + "    public static final Set<String> NAMES = Set.of(\"a\");\n"
                + "    private static final String TEXT = \"public static fake(int x) { }\";\n"
                + "    public enum Mode { A, B }\n"
                + "    public record Result(int kind, String reason) {\n"
                + "        public static Result refused(String reason) { return new Result(0, reason); }\n"
                + "    }\n"
                + "    public static final class Book { public static int size() { return 0; } }\n"
                + "    public static Result withdraw(int a, java.util.function.BooleanSupplier b) {\n"
                + "        if (a > 0) { return new Result(1, \"}\"); }\n"
                + "        char c = '{';\n"
                + "        return null;\n"
                + "    }\n"
                + "    public static <T extends Comparable<T>> List<T> sorted(List<T> in) { return in; }\n"
                + "    public static int[] counts() { return new int[0]; }\n"
                + "    static void packagePrivate() { }\n"
                + "    private static void hidden() { }\n"
                + "    public void instance() { }\n"
                + "}\n");
        assertEquals(Set.of("withdraw", "sorted", "counts"), publicStaticMethods(source));
    }

    @Test
    void commentsAreStrippedBeforeScanning() {
        assertFalse(stripComments("// SupplyRequestService.request(a)\n/* transferNow( */ int x;").contains("request("));
        assertTrue(stripComments("a(); // note\nb();").contains("b();"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * The names of the {@code public static} methods declared directly in the one top-level class
     * of {@code source} (comments already stripped): nested types' members, fields and method
     * bodies are not read.
     */
    private static Set<String> publicStaticMethods(String source) {
        String members = topLevelMemberText(stripLiterals(source));
        Set<String> names = new TreeSet<>();
        Matcher m = PUBLIC_STATIC_MEMBER.matcher(members);
        while (m.find()) {
            if (!TYPE_KEYWORD.matcher(m.group(1)).find()) {
                names.add(m.group(2));
            }
        }
        return names;
    }

    /**
     * {@code source} with everything inside a member's braces (method bodies, nested type bodies,
     * initializers) blanked out. The braces that open and close those blocks stay, so a member
     * declaration never runs on into the next one.
     */
    private static String topLevelMemberText(String source) {
        StringBuilder kept = new StringBuilder(source.length());
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
                kept.append(depth <= 2 ? c : ' ');
            } else if (c == '}') {
                kept.append(depth <= 2 ? c : ' ');
                depth--;
            } else {
                kept.append(depth <= 1 ? c : ' ');
            }
        }
        return kept.toString();
    }

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"");
    private static final Pattern LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])'");

    /** Blanks text blocks, string and char literals, so braces or names inside them are not read as code. */
    private static String stripLiterals(String source) {
        String noBlocks = TEXT_BLOCK.matcher(source).replaceAll("\"\"");
        return LITERAL.matcher(noBlocks).replaceAll(m -> m.group().startsWith("\"") ? "\"\"" : "' '");
    }

    /** Removes block and line comments; string literals are left alone (none of the guarded shapes are URLs). */
    private static String stripComments(String source) {
        String noBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlocks.replaceAll("(?m)(?<![:\"])//[^\\n]*", " ");
    }
}
