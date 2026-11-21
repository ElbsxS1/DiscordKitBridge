package br.asthen.kitbridge;

import br.asthen.kitbridge.api.ApiServer;
import br.asthen.kitbridge.api.OutboxStore;
import br.asthen.kitbridge.api.PollClient;
import br.asthen.kitbridge.core.*;
import br.asthen.kitbridge.core.GameAdapter.GiveResult;
import br.asthen.kitbridge.core.GameAdapter.Lookup;
import br.asthen.kitbridge.core.GameAdapter.Outcome;
import br.asthen.kitbridge.kit.*;
import br.asthen.kitbridge.logs.DeliveryLogger;
import br.asthen.kitbridge.security.Authentication;
import br.asthen.kitbridge.security.RateLimiter;
import br.asthen.kitbridge.security.Validators;
import br.asthen.kitbridge.util.Json;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Self-contained tests (JDK only). They cover everything that does not need a running Paper server:
 * storage, serialization round-trip, security, idempotency, the delivery pipeline (against a fake game),
 * the REST API over real HTTP, and the poll client against a fake bot.
 * Run with scripts/run-tests.sh.
 */
public final class TestMain {

    static int passed, failed;
    static final List<String> failures = new ArrayList<>();
    static final String TOKEN = "t".repeat(40);
    static Path root;

    // ============================================================ fake game

    static final class FakeGame implements GameAdapter {
        final Map<String, PlayerRef> online = new HashMap<>();      // lower-case name
        final Map<String, Platform> platformOf = new HashMap<>();
        final Set<UUID> full = new HashSet<>();
        final List<String> given = Collections.synchronizedList(new ArrayList<>());
        final List<String> told = Collections.synchronizedList(new ArrayList<>());
        boolean unverifiable, corrupt;
        volatile Runnable onGive; // test hook: runs at the moment items reach the inventory

        PlayerRef join(String name, Platform p) {
            PlayerRef r = new PlayerRef(UUID.nameUUIDFromBytes(name.getBytes()), name);
            online.put(name.toLowerCase(), r);
            platformOf.put(name.toLowerCase(), p);
            return r;
        }

        public Lookup find(String name, Platform platform) {
            String k = Names.normalize(name, ".");
            PlayerRef r = online.get(k);
            if (r == null) return Lookup.of(Outcome.OFFLINE);
            if (unverifiable && platform == Platform.BEDROCK) return Lookup.of(Outcome.UNVERIFIABLE);
            if (platformOf.get(k) != platform) return Lookup.of(Outcome.MISMATCH);
            return new Lookup(Outcome.ONLINE, r, platform);
        }

        public synchronized GiveResult give(PlayerRef p, KitData kit) {
            if (corrupt) return GiveResult.CORRUPT;
            if (!online.containsKey(p.name().toLowerCase())) return GiveResult.OFFLINE;
            if (full.contains(p.uuid())) return GiveResult.FULL;
            given.add(p.name() + ":" + kit.name());
            Runnable h = onGive;
            if (h != null) h.run();
            return GiveResult.DELIVERED;
        }

        public void tell(PlayerRef p, String m) { told.add(p.name() + ":" + m); }
    }

    // ============================================================ harness

    static final class Env implements AutoCloseable {
        final Path dir;
        final FakeGame game = new FakeGame();
        final KitManager kits;
        final RequestStore store;
        final PendingStore pending;
        final DeliveryLogger log;
        volatile DeliveryOptions opts = new DeliveryOptions(true, true, true, true, 5, ".");
        Executor main = Runnable::run;
        final DeliveryService service;

        Env() throws Exception {
            dir = Files.createTempDirectory(root, "env");
            log = new DeliveryLogger(dir.resolve("logs"), true, null);
            kits = new KitManager(new KitStorage(dir.resolve("kits"), m -> {}), () -> {});
            store = new RequestStore(dir.resolve("requests.json"), 1000, 86_400_000L, System::currentTimeMillis, m -> {});
            pending = new PendingStore(dir.resolve("pending"));
            service = new DeliveryService(game, kits, store, pending, log, r -> main.execute(r), () -> opts);
            kits.reload();
        }

        void addKit(String name) throws Exception {
            kits.create(kit(name));
        }

        public void close() {
            store.close();
            log.close();
        }
    }

    static KitData kit(String name) {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i; // every possible byte value must survive
        return new KitData(name, 1_700_000_000_000L, "admin", true,
                List.of(new KitData.Slot(0, all), new KitData.Slot(5, "diamond_sword{ench,lore,name}".getBytes(StandardCharsets.UTF_8)),
                        new KitData.Slot(26, new byte[]{1, 2, 3})));
    }

    static KitRequest req(String id, String player, Platform p, String kit) {
        return new KitRequest(id, player, p, kit);
    }

    static RequestRecord run(Env e, KitRequest r) throws Exception {
        return e.service.submit(r).get(10, TimeUnit.SECONDS);
    }

    static void test(String name, ThrowingRunnable body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + ": " + t);
            System.out.println("  FAIL  " + name + "  -> " + t);
            if (!(t instanceof AssertionError)) t.printStackTrace(System.out);
        }
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    static void ok(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    // ============================================================ HTTP helpers

    record Http(int code, String body) {
        Map<String, Object> json() throws Exception { return Json.parseObject(body); }
    }

    static final HttpClient CLIENT = HttpClient.newHttpClient();

    static Http call(String method, String url, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(10));
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Http(r.statusCode(), r.body());
    }

    static String body(String id, String player, String platform, String kit) {
        return "{\"request_id\":\"" + id + "\",\"player\":\"" + player + "\",\"platform\":\"" + platform + "\",\"kit\":\"" + kit + "\"}";
    }

    // ============================================================ main

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("dkb-tests");
        System.out.println("DiscordKitBridge tests (temp dir: " + root + ")\n");

        section("Json");
        test("round trip with unicode, escapes and nesting", () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("s", "ação \"q\" \\ \n\t \u0001 日本");
            m.put("n", 42L);
            m.put("d", 1.5);
            m.put("b", true);
            m.put("z", null);
            m.put("l", List.of(1L, "x", List.of()));
            Map<String, Object> back = Json.parseObject(Json.write(m));
            eq(m.get("s"), back.get("s"), "string");
            eq(42L, back.get("n"), "long");
            eq(List.of(1L, "x", List.of()), back.get("l"), "list");
        });
        test("rejects malformed JSON", () -> {
            for (String bad : List.of("", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "nul", "\"unterminated", "{'a':1}"))
                try { Json.parse(bad); throw new AssertionError("accepted: " + bad); } catch (Json.JsonException expected) { }
        });
        test("rejects absurd nesting depth", () -> {
            try { Json.parse("[".repeat(500) + "]".repeat(500)); throw new AssertionError("accepted"); } catch (Json.JsonException expected) { }
        });

        section("Validação de nomes");
        test("kit names: valid and invalid", () -> {
            for (String good : List.of("VipGuloso", "kit_1", "a-b", "A".repeat(32))) ok(Validators.isValidKitName(good), good);
            for (String bad : List.of("", " ", "../etc/passwd", "a/b", "a\\b", "a b", "kit.json", "con", "NUL", "COM1", "A".repeat(33),
                    "kit\n", "kit;rm", "ação", "$(id)"))
                ok(!Validators.isValidKitName(bad), "should reject: " + bad);
            ok(!Validators.isValidKitName(null), "null");
        });
        test("player names and request ids", () -> {
            for (String good : List.of("Elber", ".Elber", "Fulano 123", "a_b")) ok(Validators.isValidPlayerName(good), good);
            for (String bad : List.of("", " x", "x ", "a;b", "a\nb", "/kill @a", "x".repeat(33), "§c"))
                ok(!Validators.isValidPlayerName(bad), "should reject: " + bad);
            ok(Validators.isValidRequestId("discord-123456"), "id");
            ok(!Validators.isValidRequestId("a b"), "space in id");
            ok(!Validators.isValidRequestId("x".repeat(65)), "long id");
        });
        test("KitRequest.parse rejects wrong types and unknown platform", () -> {
            for (Map<String, Object> bad : List.of(
                    Map.<String, Object>of("request_id", 1L, "player", "a", "platform", "java", "kit", "k"),
                    Map.<String, Object>of("request_id", "r", "player", "a", "platform", "console", "kit", "k"),
                    Map.<String, Object>of("request_id", "r", "player", "a", "platform", "java"),
                    Map.<String, Object>of("request_id", "r", "player", "a", "platform", "java", "kit", "/give @a diamond")))
                try { KitRequest.parse(bad); throw new AssertionError("accepted " + bad); } catch (KitRequest.InvalidRequestException expected) { }
            KitRequest ok = KitRequest.parse(Map.of("request_id", "r", "player", "Elber", "platform", "Bedrock", "kit", "VipGuloso"));
            eq(Platform.BEDROCK, ok.platform(), "platform parsed case-insensitively");
        });

        section("Autenticação e rate limit");
        test("token: valid, invalid, missing, wrong scheme", () -> {
            Authentication a = new Authentication(TOKEN);
            ok(a.verify("Bearer " + TOKEN), "valid");
            ok(a.verify("bearer  " + TOKEN + " "), "lenient case/whitespace");
            ok(!a.verify("Bearer " + TOKEN + "x"), "wrong");
            ok(!a.verify(null), "missing");
            ok(!a.verify(TOKEN), "no scheme");
            ok(!a.verify("Basic " + TOKEN), "basic");
            ok(!a.verify("Bearer "), "empty");
        });
        test("weak secrets are refused; generated tokens are strong and unique", () -> {
            for (String weak : new String[]{null, "", "CHANGE_ME", "short"})
                try { new Authentication(weak); throw new AssertionError("accepted " + weak); } catch (IllegalArgumentException expected) { }
            String t1 = Authentication.generateToken(), t2 = Authentication.generateToken();
            ok(t1.length() >= 40 && !t1.equals(t2) && !Authentication.isWeak(t1), "generated token");
        });
        test("rate limiter: limits per key and recovers after the window", () -> {
            long[] now = {0};
            RateLimiter rl = new RateLimiter(3, () -> now[0]);
            for (int i = 0; i < 3; i++) ok(rl.tryAcquire("a"), "within limit " + i);
            ok(!rl.tryAcquire("a"), "over limit");
            ok(rl.tryAcquire("b"), "other key unaffected");
            now[0] = 60_001;
            ok(rl.tryAcquire("a"), "window passed");
            ok(new RateLimiter(0).tryAcquire("x"), "0 disables");
        });

        section("Cadastro e armazenamento de kits");
        test("kit survives a 'restart' byte-for-byte (all 256 byte values)", () -> {
            Path d = Files.createTempDirectory(root, "kits");
            KitManager m1 = new KitManager(new KitStorage(d, s -> {}), () -> {});
            m1.reload();
            m1.create(kit("VipGuloso"));
            KitManager m2 = new KitManager(new KitStorage(d, s -> {}), () -> {}); // fresh instance = server restart
            eq(1, m2.reload(), "kits loaded after restart");
            KitData back = m2.get("VipGuloso").orElseThrow();
            KitData orig = kit("VipGuloso");
            eq(orig.slots().size(), back.slots().size(), "slot count");
            for (int i = 0; i < orig.slots().size(); i++) {
                eq(orig.slots().get(i).slot(), back.slots().get(i).slot(), "slot index");
                ok(Arrays.equals(orig.slots().get(i).data(), back.slots().get(i).data()), "bytes of slot " + i);
            }
            eq("admin", back.createdBy(), "createdBy");
            eq(1_700_000_000_000L, back.createdAt(), "createdAt");
            ok(Files.exists(d.resolve("VipGuloso.json")), "file exists at kits/<name>.json");
        });
        test("duplicate name (even different case) is refused", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                for (String dup : List.of("VipGuloso", "vipguloso", "VIPGULOSO"))
                    try { e.kits.create(kit(dup)); throw new AssertionError("accepted " + dup); } catch (KitManager.KitException expected) { }
                eq(1, e.kits.list().size(), "still one kit");
            }
        });
        test("lookup is case-insensitive; delete removes file and index; toggle persists", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                ok(e.kits.get("vipguloso").isPresent(), "case-insensitive get");
                e.kits.setEnabled("VipGuloso", false);
                ok(!e.kits.get("VipGuloso").orElseThrow().enabled(), "disabled");
                KitManager again = new KitManager(new KitStorage(e.dir.resolve("kits"), s -> {}), () -> {});
                again.reload();
                ok(!again.get("VipGuloso").orElseThrow().enabled(), "disabled state persisted");
                ok(e.kits.delete("VipGuloso").isPresent(), "deleted");
                ok(e.kits.get("VipGuloso").isEmpty(), "gone from index");
                ok(!Files.exists(e.dir.resolve("kits/VipGuloso.json")), "gone from disk");
                ok(e.kits.delete("VipGuloso").isEmpty(), "second delete is a no-op");
            }
        });
        test("corrupt or mismatched kit files are skipped, never deleted", () -> {
            Path d = Files.createTempDirectory(root, "kits");
            Files.writeString(d.resolve("Broken.json"), "{not json");
            Files.writeString(d.resolve("Other.json"), Json.write(kit("Different").toMap()));
            List<String> warnings = new ArrayList<>();
            KitStorage st = new KitStorage(d, warnings::add);
            st.save(kit("Good"));
            eq(1, st.loadAll().size(), "only the good one loads");
            eq(2, warnings.size(), "two warnings");
            ok(Files.exists(d.resolve("Broken.json")) && Files.exists(d.resolve("Other.json")), "bad files untouched");
        });
        test("path traversal through a kit name is impossible", () -> {
            Path d = Files.createTempDirectory(root, "kits");
            KitStorage st = new KitStorage(d, s -> {});
            try { st.save(new KitData("../escape", 0, "x", true, List.of())); throw new AssertionError("saved"); } catch (java.io.IOException expected) { }
        });

        section("Entrega (pipeline com jogo simulado)");
        test("Bedrock player online: kit delivered, all progress states in order", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                RequestRecord r = run(e, req("discord-1", "Elber", Platform.BEDROCK, "VipGuloso"));
                eq(Status.KIT_DELIVERED, r.status(), "status");
                eq(List.of("Elber:VipGuloso"), e.game.given, "given once");
                List<String> steps = new ArrayList<>();
                for (Object h : (List<?>) r.toMap().get("history")) steps.add((String) ((Map<?, ?>) h).get("status"));
                eq(List.of("QUEUED", "LOADING_PLAYER", "PLAYER_ON", "LOADING_KIT", "DELIVERING_KIT", "KIT_DELIVERED"), steps, "history");
                Map<String, Object> j = r.toMap();
                eq(true, j.get("success"), "success flag");
                eq("bedrock", j.get("platform"), "platform echoed");
            }
        });
        test("Java player online: kit delivered", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Steve", Platform.JAVA);
                eq(Status.KIT_DELIVERED, run(e, req("j-1", "Steve", Platform.JAVA, "VipGuloso")).status(), "status");
            }
        });
        test("player offline: PLAYER_OFFLINE, nothing given", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                RequestRecord r = run(e, req("o-1", "Ninguem", Platform.JAVA, "VipGuloso"));
                eq(Status.PLAYER_OFFLINE, r.status(), "status");
                eq(false, r.toMap().get("success"), "success flag");
                eq("Player not found", r.message(), "message");
                ok(e.game.given.isEmpty(), "nothing given");
            }
        });
        test("kit does not exist: KIT_NOT_FOUND, nothing given", () -> {
            try (Env e = new Env()) {
                e.game.join("Elber", Platform.BEDROCK);
                eq(Status.KIT_NOT_FOUND, run(e, req("k-1", "Elber", Platform.BEDROCK, "Fantasma")).status(), "status");
                ok(e.game.given.isEmpty(), "nothing given");
            }
        });
        test("disabled kit is not delivered", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.kits.setEnabled("VipGuloso", false);
                e.game.join("Elber", Platform.BEDROCK);
                eq(Status.KIT_DISABLED, run(e, req("d-1", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "status");
                ok(e.game.given.isEmpty(), "nothing given");
            }
        });
        test("platform mismatch and unverifiable platform are refused", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.JAVA);
                eq(Status.PLATFORM_MISMATCH, run(e, req("m-1", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "bedrock requested, java online");
                e.game.unverifiable = true;
                eq(Status.PLATFORM_UNVERIFIABLE, run(e, req("m-2", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "no Floodgate");
                ok(e.game.given.isEmpty(), "nothing given");
            }
        });
        test("delivery disabled in config", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                e.opts = new DeliveryOptions(false, true, true, true, 5, ".");
                eq(Status.DELIVERY_DISABLED, run(e, req("x-1", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "status");
                ok(e.game.given.isEmpty(), "nothing given");
            }
        });
        test("corrupt kit: KIT_CORRUPT and no partial delivery", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                e.game.corrupt = true;
                eq(Status.KIT_CORRUPT, run(e, req("c-1", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "status");
                ok(e.game.given.isEmpty(), "nothing given");
            }
        });

        section("Inventário cheio / pendentes");
        test("full inventory: whole kit stored as pending, nothing partial, player told", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                PlayerRef p = e.game.join("Elber", Platform.BEDROCK);
                e.game.full.add(p.uuid());
                RequestRecord r = run(e, req("f-1", "Elber", Platform.BEDROCK, "VipGuloso"));
                eq(Status.KIT_PENDING, r.status(), "status");
                ok(e.game.given.isEmpty(), "nothing given yet");
                eq(1, e.service.pendingCount(p, Platform.BEDROCK), "one pending");
                ok(e.game.told.stream().anyMatch(s -> s.contains("inventário está cheio")), "player informed");
            }
        });
        test("claim: stays pending while full, delivered once when there is space, never twice", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                PlayerRef p = e.game.join("Elber", Platform.BEDROCK);
                e.game.full.add(p.uuid());
                run(e, req("f-2", "Elber", Platform.BEDROCK, "VipGuloso"));
                eq(0, e.service.claim(p, Platform.BEDROCK), "still full -> 0");
                eq(1, e.service.pendingCount(p, Platform.BEDROCK), "still pending");
                e.game.full.clear();
                eq(1, e.service.claim(p, Platform.BEDROCK), "now delivered");
                eq(0, e.service.claim(p, Platform.BEDROCK), "second claim delivers nothing");
                eq(List.of("Elber:VipGuloso"), e.game.given, "given exactly once");
                eq(Status.KIT_DELIVERED, e.store.get("f-2").orElseThrow().status(), "request record updated");
            }
        });
        test("pending kit is a snapshot: deleting the kit later does not take it away", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                PlayerRef p = e.game.join("Elber", Platform.BEDROCK);
                e.game.full.add(p.uuid());
                run(e, req("f-3", "Elber", Platform.BEDROCK, "VipGuloso"));
                e.kits.delete("VipGuloso");
                e.game.full.clear();
                eq(1, e.service.claim(p, Platform.BEDROCK), "still delivered");
            }
        });
        test("pending survives a restart (stored on disk)", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                PlayerRef p = e.game.join("Elber", Platform.BEDROCK);
                e.game.full.add(p.uuid());
                run(e, req("f-4", "Elber", Platform.BEDROCK, "VipGuloso"));
                PendingStore reopened = new PendingStore(e.dir.resolve("pending"));
                eq(1, reopened.count("uuid:" + p.uuid()), "pending after reopen");
                ok(Arrays.equals(kit("x").slots().get(0).data(), reopened.list("uuid:" + p.uuid()).get(0).kit().slots().get(0).data()), "bytes intact");
            }
        });
        test("pending-if-inventory-full=false: INVENTORY_FULL and nothing stored", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                PlayerRef p = e.game.join("Elber", Platform.BEDROCK);
                e.game.full.add(p.uuid());
                e.opts = new DeliveryOptions(true, true, false, true, 5, ".");
                eq(Status.INVENTORY_FULL, run(e, req("f-5", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "status");
                eq(0, e.service.pendingCount(p, Platform.BEDROCK), "nothing pending");
            }
        });
        test("require-online=false: offline player gets the kit when they join", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.opts = new DeliveryOptions(true, false, true, true, 5, ".");
                RequestRecord r = run(e, req("off-1", "Elber", Platform.BEDROCK, "VipGuloso"));
                eq(Status.KIT_PENDING, r.status(), "queued");
                PlayerRef p = e.game.join("Elber", Platform.BEDROCK);
                eq(1, e.service.claim(p, Platform.BEDROCK), "delivered on join");
                eq(0, e.service.claim(p, Platform.BEDROCK), "only once");
                // a different platform with the same name must NOT receive it
                try (Env e2 = new Env()) {
                    e2.addKit("VipGuloso");
                    e2.opts = new DeliveryOptions(true, false, true, true, 5, ".");
                    run(e2, req("off-2", "Elber", Platform.BEDROCK, "VipGuloso"));
                    PlayerRef javaElber = e2.game.join("Elber", Platform.JAVA);
                    eq(0, e2.service.claim(javaElber, Platform.JAVA), "Java 'Elber' does not get Bedrock 'Elber's kit");
                }
            }
        });

        section("Preço, ativar/desativar e caixa do kit");
        test("Prices.parse: valid inputs become exact cents; invalid ones are rejected", () -> {
            eq(3000L, br.asthen.kitbridge.util.Prices.parse("30").getAsLong(), "30");
            eq(2990L, br.asthen.kitbridge.util.Prices.parse("29,90").getAsLong(), "29,90");
            eq(2990L, br.asthen.kitbridge.util.Prices.parse("29.90").getAsLong(), "29.90");
            eq(2950L, br.asthen.kitbridge.util.Prices.parse("29,5").getAsLong(), "29,5 means 29,50");
            eq(1L, br.asthen.kitbridge.util.Prices.parse("0,01").getAsLong(), "one cent");
            eq(99_999_999L, br.asthen.kitbridge.util.Prices.parse("999999,99").getAsLong(), "maximum");
            for (String bad : List.of("", "0", "0,00", "-5", "abc", "29,999", "1.234,56", "1e3", "30 reais", "9999999", " ", "+5", "NaN"))
                ok(br.asthen.kitbridge.util.Prices.parse(bad).isEmpty(), "should reject: " + bad);
            ok(br.asthen.kitbridge.util.Prices.parse(null).isEmpty(), "null");
            eq("R$ 29,90", br.asthen.kitbridge.util.Prices.format(2990), "format");
            eq("R$ 30,05", br.asthen.kitbridge.util.Prices.format(3005), "format with leading zero cent");
        });
        test("price survives a restart; changing it never touches the items", () -> {
            try (Env e = new Env()) {
                e.addKit("Kit_Dima_Evo");
                ok(!e.kits.get("Kit_Dima_Evo").orElseThrow().hasPrice(), "starts without price");
                byte[] before = e.kits.get("Kit_Dima_Evo").orElseThrow().slots().get(0).data();
                e.kits.setPrice("Kit_Dima_Evo", 3000);
                e.kits.setPrice("kit_dima_evo", 4590); // raise the price later, case-insensitive name
                KitManager again = new KitManager(new KitStorage(e.dir.resolve("kits"), m -> {}), () -> {});
                again.reload();
                KitData k = again.get("Kit_Dima_Evo").orElseThrow();
                eq(4590L, k.priceCents(), "last price persisted");
                ok(Arrays.equals(before, k.slots().get(0).data()), "items untouched");
                eq(3, k.itemCount(), "same item count");
                e.kits.setPrice("Kit_Dima_Evo", KitData.NO_PRICE);
                ok(!e.kits.get("Kit_Dima_Evo").orElseThrow().hasPrice(), "price removed");
                ok(e.kits.setPrice("Fantasma", 100).isEmpty(), "unknown kit is a no-op");
            }
        });
        test("kits saved by an older version (no price, no box) still load", () -> {
            Path d = Files.createTempDirectory(root, "old");
            Map<String, Object> m = kit("Antigo").toMap();
            ok(!m.containsKey("price_cents") && !m.containsKey("box"), "old format has neither field");
            Files.writeString(d.resolve("Antigo.json"), Json.write(m));
            KitData k = new KitStorage(d, x -> {}).loadAll().get("antigo");
            eq(KitData.NO_PRICE, k.priceCents(), "no price");
            eq(null, k.box(), "default box");
            ok(k.enabled(), "enabled");
        });
        test("box colour round-trips; a malformed box value is ignored", () -> {
            KitData blue = new KitData("Azul", 1L, "a", true, kit("x").slots(), 2990, "BLUE_SHULKER_BOX");
            KitData back = KitData.fromMap(Json.parseObject(Json.write(blue.toMap())));
            eq("BLUE_SHULKER_BOX", back.box(), "box");
            eq(2990L, back.priceCents(), "price");
            Map<String, Object> bad = blue.toMap();
            bad.put("box", "../../etc/passwd");
            eq(null, KitData.fromMap(bad).box(), "malformed box ignored");
            bad.put("price_cents", -5L);
            eq(KitData.NO_PRICE, KitData.fromMap(bad).priceCents(), "negative price ignored");
        });
        test("pending kit keeps its price and box (snapshot)", () -> {
            Path d = Files.createTempDirectory(root, "pend");
            PendingStore ps = new PendingStore(d);
            KitData k = new KitData("Azul", 1L, "a", true, kit("x").slots(), 2990, "BLUE_SHULKER_BOX");
            ps.add("uuid:x", new PendingStore.Entry("r1", "Azul", 1L, k));
            KitData back = ps.list("uuid:x").get(0).kit();
            eq("BLUE_SHULKER_BOX", back.box(), "box kept");
            eq(2990L, back.priceCents(), "price kept");
        });
        test("enable/disable: a disabled kit is not delivered and comes back when enabled", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                e.kits.setEnabled("VipGuloso", false);
                eq(Status.KIT_DISABLED, run(e, req("en-1", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "disabled");
                e.kits.setEnabled("VipGuloso", true);
                eq(Status.KIT_DELIVERED, run(e, req("en-2", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "enabled again");
            }
        });
        try (Env e = new Env()) {
            e.addKit("Pago");
            e.kits.setPrice("Pago", 2990);
            e.addKit("SemPreco");
            e.addKit("Parado");
            e.kits.setPrice("Parado", 1000);
            e.kits.setEnabled("Parado", false);
            ApiServer api = new ApiServer("127.0.0.1", 0, new Authentication(TOKEN), new RateLimiter(1000), e.service, e.log, 5, 4096);
            api.start();
            test("kit list for the bot carries price, status and for_sale (disabled/unpriced = unavailable)", () -> {
                Http h = call("GET", "http://127.0.0.1:" + api.boundPort() + "/api/v1/kits", TOKEN, null);
                Map<String, Map<?, ?>> byName = new HashMap<>();
                for (Object o : (List<?>) h.json().get("kits")) byName.put((String) ((Map<?, ?>) o).get("name"), (Map<?, ?>) o);
                eq(3, byName.size(), "three kits");
                eq(2990L, byName.get("Pago").get("price_cents"), "price_cents");
                eq(29.9, byName.get("Pago").get("price"), "price in reais");
                eq(true, byName.get("Pago").get("for_sale"), "priced + enabled is for sale");
                eq(null, byName.get("SemPreco").get("price"), "no price -> null");
                eq(false, byName.get("SemPreco").get("for_sale"), "no price is not for sale");
                eq(false, byName.get("Parado").get("enabled"), "disabled flag");
                eq(false, byName.get("Parado").get("for_sale"), "disabled is unavailable");
            });
            api.stop();
        }

        section("/dk give (entrega direta do admin)");
        test("give: delivered whole; full inventory parks the whole kit; claim then delivers once", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                PlayerRef p = e.game.join("Elber", Platform.BEDROCK);
                KitData k = e.kits.get("VipGuloso").orElseThrow();
                eq(DeliveryService.DirectOutcome.DELIVERED, e.service.giveDirect(p, k, "admin"), "delivered");
                eq(1, e.game.given.size(), "given");
                e.game.full.add(p.uuid());
                eq(DeliveryService.DirectOutcome.PENDING, e.service.giveDirect(p, k, "admin"), "full -> pending");
                eq(1, e.game.given.size(), "nothing partial while full");
                e.game.full.clear();
                eq(1, e.service.claim(p, Platform.BEDROCK), "claim delivers it");
                eq(0, e.service.claim(p, Platform.BEDROCK), "only once");
                eq(2, e.game.given.size(), "total");
            }
        });
        test("give: pending disabled -> FULL and nothing stored; corrupt kit -> CORRUPT", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                PlayerRef p = e.game.join("Elber", Platform.JAVA);
                KitData k = e.kits.get("VipGuloso").orElseThrow();
                e.opts = new DeliveryOptions(true, true, false, true, 5, ".");
                e.game.full.add(p.uuid());
                eq(DeliveryService.DirectOutcome.FULL, e.service.giveDirect(p, k, "admin"), "full");
                eq(0, e.service.pendingCount(p, Platform.JAVA), "nothing stored");
                e.game.full.clear();
                e.game.corrupt = true;
                eq(DeliveryService.DirectOutcome.CORRUPT, e.service.giveDirect(p, k, "admin"), "corrupt");
                ok(e.game.given.isEmpty(), "nothing given");
            }
        });

        section("Duplicidade e timeout");
        test("same request_id twice: processed once, second call returns the original result", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                RequestRecord a = run(e, req("dup-1", "Elber", Platform.BEDROCK, "VipGuloso"));
                RequestRecord b = run(e, req("dup-1", "Elber", Platform.BEDROCK, "VipGuloso"));
                ok(a == b, "same record");
                eq(Status.KIT_DELIVERED, b.status(), "original result returned");
                eq(1, e.game.given.size(), "delivered exactly once");
                // even a replay with a different payload cannot sneak a second kit through
                run(e, req("dup-1", "Elber", Platform.BEDROCK, "OutroKit"));
                eq(1, e.game.given.size(), "still once");
            }
        });
        test("100 concurrent submissions of one request_id deliver exactly once", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                ExecutorService pool = Executors.newFixedThreadPool(16);
                List<Future<RequestRecord>> fs = new ArrayList<>();
                for (int i = 0; i < 100; i++) fs.add(pool.submit(() -> run(e, req("race-1", "Elber", Platform.BEDROCK, "VipGuloso"))));
                for (Future<RequestRecord> f : fs) f.get(15, TimeUnit.SECONDS);
                pool.shutdown();
                eq(1, e.game.given.size(), "given exactly once");
            }
        });
        test("duplicate request_id survives a restart", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                run(e, req("dup-2", "Elber", Platform.BEDROCK, "VipGuloso"));
                e.store.close(); // flush
                RequestStore reopened = new RequestStore(e.dir.resolve("requests.json"), 1000, 86_400_000L, System::currentTimeMillis, m -> {});
                reopened.load();
                eq(Status.KIT_DELIVERED, reopened.get("dup-2").orElseThrow().status(), "remembered");
                ok(reopened.register(req("dup-2", "Elber", Platform.BEDROCK, "VipGuloso")).duplicate(), "still a duplicate");
                reopened.close();
            }
        });
        test("request interrupted by a restart is closed as INTERNAL_ERROR, not redelivered", () -> {
            Path f = Files.createTempDirectory(root, "rs").resolve("requests.json");
            RequestStore s1 = new RequestStore(f, 100, 86_400_000L, System::currentTimeMillis, m -> {});
            s1.register(req("mid-1", "Elber", Platform.JAVA, "K")); // left in QUEUED
            s1.close();
            RequestStore s2 = new RequestStore(f, 100, 86_400_000L, System::currentTimeMillis, m -> {});
            s2.load();
            eq(Status.INTERNAL_ERROR, s2.get("mid-1").orElseThrow().status(), "closed");
            s2.close();
        });
        test("identical player+kit while another is in flight: DUPLICATE_REQUEST", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                List<Runnable> held = new ArrayList<>();
                e.main = held::add; // server thread is 'busy': tasks queue up
                CompletableFuture<RequestRecord> first = e.service.submit(req("in-1", "Elber", Platform.BEDROCK, "VipGuloso"));
                RequestRecord second = e.service.submit(req("in-2", "Elber", Platform.BEDROCK, "VipGuloso")).get(5, TimeUnit.SECONDS);
                eq(Status.DUPLICATE_REQUEST, second.status(), "second refused");
                held.forEach(Runnable::run);
                eq(Status.KIT_DELIVERED, first.get(5, TimeUnit.SECONDS).status(), "first delivered");
                eq(1, e.game.given.size(), "given once");
                // and once the first finished, a new request is fine again
                e.main = Runnable::run;
                eq(Status.KIT_DELIVERED, run(e, req("in-3", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "allowed again");
            }
        });
        test("server thread never gets to it: TIMEOUT, and a late task does nothing", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                List<Runnable> held = new ArrayList<>();
                e.main = held::add;
                e.opts = new DeliveryOptions(true, true, true, true, 1, ".");
                RequestRecord r = e.service.submit(req("to-1", "Elber", Platform.BEDROCK, "VipGuloso")).get(10, TimeUnit.SECONDS);
                eq(Status.TIMEOUT, r.status(), "timed out");
                held.forEach(Runnable::run); // the server finally gets around to it
                ok(e.game.given.isEmpty(), "late task must not deliver a request already reported as timed out");
            }
        });
        test("server shutting down: INTERNAL_ERROR instead of hanging", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.main = r -> { throw new RejectedExecutionException("plugin disabled"); };
                eq(Status.INTERNAL_ERROR, run(e, req("sd-1", "Elber", Platform.BEDROCK, "VipGuloso")).status(), "status");
            }
        });

        section("API REST (HTTP real em loopback)");
        try (Env e = new Env()) {
            e.addKit("VipGuloso");
            e.game.join("Elber", Platform.BEDROCK);
            ApiServer api = new ApiServer("127.0.0.1", 0, new Authentication(TOKEN), new RateLimiter(1000), e.service, e.log, 5, 512);
            api.start();
            String base = "http://127.0.0.1:" + api.boundPort() + "/api/v1";

            test("GET /health works and exposes nothing sensitive", () -> {
                Http h = call("GET", base + "/health", null, null);
                eq(200, h.code(), "code");
                eq(true, h.json().get("success"), "success");
                ok(!h.body().contains(TOKEN), "no token");
            });
            test("token invalid / missing -> 401 on every protected endpoint", () -> {
                for (String[] c : new String[][]{{"GET", "/kits"}, {"GET", "/kits/status/x"}, {"POST", "/kits/request"}}) {
                    String b = c[0].equals("POST") ? body("a-1", "Elber", "bedrock", "VipGuloso") : null;
                    Http noTok = call(c[0], base + c[1], null, b);
                    Http badTok = call(c[0], base + c[1], "x".repeat(40), b);
                    eq(401, noTok.code(), c[1] + " without token");
                    eq(401, badTok.code(), c[1] + " with wrong token");
                    eq("UNAUTHORIZED", noTok.json().get("status"), "status");
                }
                ok(e.game.given.isEmpty(), "nothing delivered by unauthenticated calls");
            });
            test("POST /kits/request delivers and returns the documented JSON", () -> {
                Http h = call("POST", base + "/kits/request", TOKEN, body("discord-123456", "Elber", "bedrock", "VipGuloso"));
                eq(200, h.code(), "code");
                Map<String, Object> j = h.json();
                eq(true, j.get("success"), "success");
                eq("KIT_DELIVERED", j.get("status"), "status");
                eq("discord-123456", j.get("request_id"), "request_id");
                eq("Elber", j.get("player"), "player");
                eq("bedrock", j.get("platform"), "platform");
                eq("VipGuloso", j.get("kit"), "kit");
                eq("Kit delivered successfully", j.get("message"), "message");
                eq(1, e.game.given.size(), "delivered");
            });
            test("same request again: 200 with original result, not delivered twice", () -> {
                Http h = call("POST", base + "/kits/request", TOKEN, body("discord-123456", "Elber", "bedrock", "VipGuloso"));
                eq("KIT_DELIVERED", h.json().get("status"), "status");
                eq(1, e.game.given.size(), "still once");
            });
            test("GET /kits/status/{id}: known 200, unknown 404, malformed 400", () -> {
                eq("KIT_DELIVERED", call("GET", base + "/kits/status/discord-123456", TOKEN, null).json().get("status"), "known");
                eq(404, call("GET", base + "/kits/status/nao-existe", TOKEN, null).code(), "unknown");
                eq(400, call("GET", base + "/kits/status/bad%20id%3B", TOKEN, null).code(), "malformed");
            });
            test("GET /kits lists kits", () -> {
                Http h = call("GET", base + "/kits", TOKEN, null);
                List<?> kits = (List<?>) h.json().get("kits");
                eq(1, kits.size(), "one kit");
                eq("VipGuloso", ((Map<?, ?>) kits.get(0)).get("name"), "name");
                eq(3L, ((Map<?, ?>) kits.get(0)).get("items"), "item count");
            });
            test("player offline over HTTP: 404 PLAYER_OFFLINE with the documented shape", () -> {
                Http h = call("POST", base + "/kits/request", TOKEN, body("off-http", "Ninguem", "java", "VipGuloso"));
                eq(404, h.code(), "code");
                eq(false, h.json().get("success"), "success");
                eq("PLAYER_OFFLINE", h.json().get("status"), "status");
                eq("Player not found", h.json().get("message"), "message");
            });
            test("kit does not exist over HTTP: 404 KIT_NOT_FOUND", () -> {
                eq("KIT_NOT_FOUND", call("POST", base + "/kits/request", TOKEN, body("nk-1", "Elber", "bedrock", "Fantasma")).json().get("status"), "status");
            });
            test("invalid input is rejected with 400 and never reaches the game", () -> {
                int before = e.game.given.size();
                for (String bad : List.of(
                        "not json", "[]", "{}", "",
                        body("r1", "Elber", "xbox", "VipGuloso"),
                        body("r2", "Elber", "java", "../../etc/passwd"),
                        body("r3", "/op Elber", "java", "VipGuloso"),
                        body("r4 x", "Elber", "java", "VipGuloso"),
                        "{\"request_id\":\"r5\",\"player\":\"Elber\",\"platform\":\"java\",\"kit\":[\"a\"]}",
                        "{\"request_id\":\"r6\",\"player\":\"Elber\",\"platform\":\"java\",\"kit\":\"VipGuloso\",\"command\":\"op Elber\"}x")) {
                    Http h = call("POST", base + "/kits/request", TOKEN, bad);
                    eq(400, h.code(), "should be 400 for: " + bad);
                    eq("INVALID_REQUEST", h.json().get("status"), "status for: " + bad);
                }
                eq(before, e.game.given.size(), "nothing delivered");
            });
            test("extra fields (like a command) are ignored: no arbitrary command execution exists", () -> {
                Http h = call("POST", base + "/kits/request", TOKEN,
                        "{\"request_id\":\"extra-1\",\"player\":\"Elber\",\"platform\":\"bedrock\",\"kit\":\"VipGuloso\",\"command\":\"op Elber\"}");
                eq("KIT_DELIVERED", h.json().get("status"), "treated as a normal request");
            });
            test("oversized body -> 413", () -> {
                eq(413, call("POST", base + "/kits/request", TOKEN, "{\"request_id\":\"" + "a".repeat(2000) + "\"}").code(), "code");
            });
            test("unknown path 404, wrong method 405", () -> {
                eq(404, call("GET", base + "/nada", TOKEN, null).code(), "404");
                eq(405, call("GET", base + "/kits/request", TOKEN, null).code(), "405");
                eq(405, call("POST", base + "/health", null, "{}").code(), "405 health");
            });
            api.stop();

            // rate limit on a fresh server
            ApiServer limited = new ApiServer("127.0.0.1", 0, new Authentication(TOKEN), new RateLimiter(5), e.service, e.log, 5, 4096);
            limited.start();
            String lb = "http://127.0.0.1:" + limited.boundPort() + "/api/v1";
            test("rate limit: 6th call within a minute -> 429", () -> {
                for (int i = 0; i < 5; i++) eq(200, call("GET", lb + "/health", null, null).code(), "call " + i);
                Http h = call("GET", lb + "/health", null, null);
                eq(429, h.code(), "code");
                eq("RATE_LIMITED", h.json().get("status"), "status");
                eq(429, call("GET", lb + "/kits", "wrong", null).code(), "limit applies before auth, so brute force is throttled");
            });
            limited.stop();
        }

        section("Cliente de polling (bot falso)");
        test("pull -> deliver -> result posted; kits synced; token sent", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                bot.queue.add(body("poll-1", "Elber", "bedrock", "VipGuloso"));
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, 5, 5);
                pc.pollOnce();
                waitFor(() -> bot.resultBodies.size() == 1, 5000, "result posted");
                Map<String, Object> j = Json.parseObject(bot.resultBodies.get(0));
                eq("KIT_DELIVERED", j.get("status"), "status");
                eq("poll-1", j.get("request_id"), "request id");
                eq(Boolean.TRUE, j.get("final"), "final flag");
                eq(List.of("Elber:VipGuloso"), e.game.given, "delivered once");
                ok(bot.sawToken.get() && !bot.sawBadToken.get(), "bearer token sent on every call");
                eq(1, bot.kitSyncs.get(), "kit list synced once");
                pc.pollOnce();
                eq(1, bot.kitSyncs.get(), "not synced again while unchanged");
            }
        });
        test("bot offers the same request again: answered from history, not redelivered", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, 5, 5);
                bot.queue.add(body("poll-2", "Elber", "bedrock", "VipGuloso"));
                pc.pollOnce();
                waitFor(() -> bot.resultBodies.size() == 1, 5000, "first result");
                bot.queue.add(body("poll-2", "Elber", "bedrock", "VipGuloso")); // lease expired on the bot side
                pc.pollOnce();
                waitFor(() -> bot.resultBodies.size() == 2, 5000, "second result");
                eq(1, e.game.given.size(), "delivered exactly once");
                eq("KIT_DELIVERED", Json.parseObject(bot.resultBodies.get(1)).get("status"), "same result re-sent");
            }
        });
        test("invalid request from the bot gets INVALID_REQUEST back and delivers nothing", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                e.addKit("VipGuloso");
                bot.queue.add(body("poll-bad", "Elber", "java", "../../x"));
                new PollClient(bot.url(), TOKEN, e.service, e.log, 5, 5).pollOnce();
                waitFor(() -> bot.resultBodies.size() == 1, 5000, "error posted");
                eq("INVALID_REQUEST", Json.parseObject(bot.resultBodies.get(0)).get("status"), "status");
            }
        });
        test("never processes more than max-per-poll even if the bot sends more", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                e.addKit("VipGuloso");
                for (int i = 0; i < 10; i++) { e.game.join("P" + i, Platform.JAVA); bot.queue.add(body("many-" + i, "P" + i, "java", "VipGuloso")); }
                bot.ignoreLimit = true;
                new PollClient(bot.url(), TOKEN, e.service, e.log, 3, 5).pollOnce();
                waitFor(() -> bot.resultBodies.size() == 3, 5000, "3 results");
                Thread.sleep(200);
                eq(3, e.game.given.size(), "only 3 delivered");
            }
        });
        test("wrong token (bot answers 401): no delivery, no exception, backs off", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                bot.queue.add(body("poll-3", "Elber", "bedrock", "VipGuloso"));
                PollClient pc = new PollClient(bot.url(), "w".repeat(40), e.service, e.log, 5, 5);
                pc.pollOnce();
                ok(e.game.given.isEmpty(), "nothing delivered");
                int hitsAfterFirst = bot.pulls.get();
                pc.pollOnce(); // inside the back-off window: must not hit the bot again
                eq(hitsAfterFirst, bot.pulls.get(), "backing off");
            }
        });
        test("bot unreachable: pollOnce returns quietly, then works once the bot is back", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                int port;
                try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) { port = ss.getLocalPort(); } // closed again: nobody listens
                PollClient pc = new PollClient("http://127.0.0.1:" + port, TOKEN, e.service, e.log, 5, 2);
                pc.pollOnce(); // must not throw
                ok(e.game.given.isEmpty(), "nothing delivered");
            }
        });
        test("result POST fails first: kept in the outbox and re-sent on the next poll", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                bot.failResultsFirst.set(1);
                bot.queue.add(body("poll-4", "Elber", "bedrock", "VipGuloso"));
                long[] now = {1_000_000L};
                OutboxStore ob = new OutboxStore(null, () -> now[0], m -> { });
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, ob, PollClient.Options.classic(5, 5));
                pc.pollOnce();
                waitFor(() -> pc.outboxSize() == 1, 5000, "result parked in outbox");
                waitFor(() -> { OutboxStore.Item it = ob.get("result:poll-4"); return it != null && it.attempts == 1; }, 5000, "first attempt failed and was scheduled for later");
                eq(1, e.game.given.size(), "delivered");
                now[0] += 60_000L; // the retry is not immediate any more (progressive backoff)
                pc.pollOnce();
                waitFor(() -> pc.outboxSize() == 0, 5000, "outbox drained");
                eq(1, e.game.given.size(), "still delivered only once");
                eq("KIT_DELIVERED", Json.parseObject(bot.resultBodies.get(bot.resultBodies.size() - 1)).get("status"), "re-sent");
            }
        });

        section("Ponta a ponta: bot Node real <-> PollClient real");
        test("bot (asthen-bridge.js) pede a entrega, o plugin entrega, o bot recebe KIT_DELIVERED", () -> {
            Process probe;
            try { probe = new ProcessBuilder("node", "-v").redirectErrorStream(true).start(); probe.waitFor(); }
            catch (java.io.IOException noNode) { System.out.println("        (node não encontrado: teste pulado)"); return; }
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                ProcessBuilder pb = new ProcessBuilder("node", "scripts/e2e-bot.js").redirectErrorStream(true);
                pb.environment().put("DKB_SECRET", TOKEN);
                Process node = pb.start();
                BlockingQueue<String> lines = new LinkedBlockingQueue<>();
                Thread reader = new Thread(() -> {
                    try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8))) {
                        String l; while ((l = br.readLine()) != null) lines.add(l);
                    } catch (java.io.IOException ignored) { }
                });
                reader.setDaemon(true);
                reader.start();
                try {
                    String portLine = lines.poll(10, TimeUnit.SECONDS);
                    ok(portLine != null && portLine.startsWith("PORT "), "bot started: " + portLine);
                    PollClient pc = new PollClient("http://127.0.0.1:" + portLine.substring(5).trim(), TOKEN, e.service, e.log, 5, 5);
                    String result = null, kitsLine = null;
                    long end = System.currentTimeMillis() + 15_000;
                    while (result == null && System.currentTimeMillis() < end) {
                        pc.pollOnce();
                        String l;
                        while ((l = lines.poll(150, TimeUnit.MILLISECONDS)) != null) {
                            if (l.startsWith("RESULT ")) result = l.substring(7);
                            else if (l.startsWith("KITS ")) kitsLine = l.substring(5);
                            else if (l.startsWith("ERROR ")) throw new AssertionError("bot error: " + l);
                        }
                    }
                    ok(result != null, "bot received a result");
                    Map<String, Object> j = Json.parseObject(result);
                    eq("KIT_DELIVERED", j.get("status"), "status seen by the bot");
                    eq("e2e-1", j.get("request_id"), "request id");
                    eq(true, j.get("success"), "success");
                    eq(List.of("Elber:VipGuloso"), e.game.given, "delivered exactly once in the game");
                    ok(kitsLine != null && kitsLine.contains("VipGuloso"), "bot learned the kit list: " + kitsLine);
                    ok(((List<?>) j.get("history")).size() >= 5, "bot got the step history for its progress messages");
                } finally {
                    node.destroyForcibly();
                }
            }
        });

        section("Logs");
        test("log lines cannot be forged with newlines and are written to a dated file", () -> {
            Path d = Files.createTempDirectory(root, "logs");
            DeliveryLogger l = new DeliveryLogger(d, true, null);
            l.log("REQUEST_RECEIVED", "player", "Elber\n2026-01-01 00:00:00 [DELIVERED] fake=1", "kit", "a=b");
            l.close();
            List<Path> files;
            try (var s = Files.list(d)) { files = s.toList(); }
            eq(1, files.size(), "one file");
            ok(files.get(0).getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.log"), "dated name");
            List<String> lines = Files.readAllLines(files.get(0));
            eq(1, lines.size(), "exactly one line despite the injected newline");
            ok(lines.get(0).contains("[REQUEST_RECEIVED]"), "event present");
        });
        test("log events cover received/identified/delivered/failed/duplicate", () -> {
            try (Env e = new Env()) {
                e.addKit("VipGuloso");
                e.game.join("Elber", Platform.BEDROCK);
                run(e, req("lg-1", "Elber", Platform.BEDROCK, "VipGuloso"));
                run(e, req("lg-1", "Elber", Platform.BEDROCK, "VipGuloso"));
                run(e, req("lg-2", "Ninguem", Platform.JAVA, "VipGuloso"));
                e.log.close();
                String all;
                try (var s = Files.walk(e.dir.resolve("logs"))) {
                    all = String.join("\n", s.filter(Files::isRegularFile).map(p -> { try { return Files.readString(p); } catch (Exception x) { return ""; } }).toList());
                }
                for (String ev : List.of("[REQUEST_RECEIVED]", "[PLAYER_IDENTIFIED]", "[DELIVERED]", "[DUPLICATE]", "[FAILED]"))
                    ok(all.contains(ev), "log has " + ev);
            }
        });

        Tests130.runAll();
        Tests140.runAll();

        // ------------------------------------------------------------ summary
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }

    static void section(String s) {
        System.out.println("\n== " + s);
    }

    static void waitFor(java.util.function.BooleanSupplier c, long ms, String what) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    // ============================================================ fake bot (the other side of poll mode)

    static final class FakeBot implements AutoCloseable {
        final HttpServer server;
        final Queue<String> queue = new ConcurrentLinkedQueue<>();
        final List<String> resultBodies = new CopyOnWriteArrayList<>();
        final BlockingQueue<Object> results = new LinkedBlockingQueue<>();
        final AtomicInteger kitSyncs = new AtomicInteger();
        final AtomicInteger pulls = new AtomicInteger();
        final AtomicInteger failResultsFirst = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean sawToken = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean sawBadToken = new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean ignoreLimit;
        final List<String> pullQueries = new CopyOnWriteArrayList<>();
        final List<String> eventBodies = new CopyOnWriteArrayList<>();
        final AtomicInteger resultStatus = new AtomicInteger();   // 0 = behave; otherwise answer this HTTP code
        final AtomicInteger eventsStatus = new AtomicInteger();
        final AtomicInteger resultPosts = new AtomicInteger();
        final AtomicInteger pullStatus = new AtomicInteger();     // 0 = behave; otherwise answer this HTTP code to pulls
        final AtomicInteger activePulls = new AtomicInteger();
        final AtomicInteger maxConcurrentPulls = new AtomicInteger();
        volatile long holdMs; // how long an empty pull is held open when wait_ms is sent (kept short for tests)

        FakeBot() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/plugin/v1/pull", ex -> {
                pulls.incrementAndGet();
                if (!authed(ex)) { send(ex, 401, "{}"); return; }
                if (pullStatus.get() != 0) { send(ex, pullStatus.get(), "{}"); return; }
                int act = activePulls.incrementAndGet();
                maxConcurrentPulls.accumulateAndGet(act, Math::max);
                try {
                String q = ex.getRequestURI().getQuery();
                pullQueries.add(q);
                int limit = 5;
                long waitMs = 0;
                for (String part : q.split("&")) {
                    String[] kv = part.split("=", 2);
                    if (kv[0].equals("limit")) limit = Integer.parseInt(kv[1]);
                    if (kv[0].equals("wait_ms")) waitMs = Long.parseLong(kv[1]);
                }
                List<String> out = new ArrayList<>();
                String next;
                long deadline = System.currentTimeMillis() + Math.min(waitMs, holdMs);
                while (true) {
                    while ((ignoreLimit || out.size() < limit) && (next = queue.poll()) != null) out.add(next);
                    if (!out.isEmpty() || System.currentTimeMillis() >= deadline) break;
                    try { Thread.sleep(10); } catch (InterruptedException ie) { break; }
                }
                if (out.isEmpty()) { ex.sendResponseHeaders(204, -1); ex.close(); return; }
                send(ex, 200, "{\"requests\":[" + String.join(",", out) + "]}");
                } finally { activePulls.decrementAndGet(); }
            });
            server.createContext("/plugin/v1/result", ex -> {
                if (!authed(ex)) { send(ex, 401, "{}"); return; }
                String b = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                resultPosts.incrementAndGet();
                if (failResultsFirst.getAndUpdate(v -> Math.max(0, v - 1)) > 0) { send(ex, 500, "{}"); return; }
                if (resultStatus.get() != 0) { send(ex, resultStatus.get(), "{\"success\":false}"); return; }
                resultBodies.add(b);
                send(ex, 200, "{\"success\":true}"); // the real bot's confirmation
            });
            server.createContext("/plugin/v1/events", ex -> {
                if (!authed(ex)) { send(ex, 401, "{}"); return; }
                String b = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (eventsStatus.get() != 0) { send(ex, eventsStatus.get(), "{\"success\":false}"); return; }
                eventBodies.add(b);
                send(ex, 200, "{\"success\":true}");
            });
            server.createContext("/plugin/v1/kits", ex -> {
                if (!authed(ex)) { send(ex, 401, "{}"); return; }
                ex.getRequestBody().readAllBytes();
                kitSyncs.incrementAndGet();
                send(ex, 200, "{}");
            });
            server.start();
        }

        boolean authed(com.sun.net.httpserver.HttpExchange ex) {
            boolean good = ("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"));
            if (good) sawToken.set(true); else sawBadToken.set(true);
            return good;
        }

        static void send(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            try (var os = ex.getResponseBody()) { os.write(b); }
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        public void close() { server.stop(0); }
    }
}
