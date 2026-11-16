package br.asthen.kitbridge;

import br.asthen.kitbridge.api.OutboxStore;
import br.asthen.kitbridge.api.PollClient;
import br.asthen.kitbridge.core.*;
import br.asthen.kitbridge.kit.PendingStore;
import br.asthen.kitbridge.kit.PendingStore.State;
import br.asthen.kitbridge.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static br.asthen.kitbridge.TestMain.*;

/**
 * Tests added for 1.3.0. Same rules as TestMain: JDK only, a fake game instead of Paper, a fake bot instead of
 * Discord. They prove the LOGIC; they do not prove behaviour inside a real Minecraft server.
 */
final class Tests130 {

    private Tests130() {
    }

    static PendingStore.Entry entry(String id, String kitName) {
        return new PendingStore.Entry(id, kitName, 1L, kit(kitName));
    }

    static Path dir(String name) throws Exception {
        return Files.createTempDirectory(root, name);
    }

    /** Makes the next atomic write to this file fail: Atomic.write needs "<file>.tmp" to be a regular file path. */
    static void blockWrites(Path file) throws Exception {
        Files.createDirectories(file.resolveSibling(file.getFileName() + ".tmp"));
    }

    static void unblockWrites(Path file) throws Exception {
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
    }

    static DeliveryService newService(Env e, PendingStore ps) {
        return new DeliveryService(e.game, e.kits, e.store, ps, e.log, Runnable::run, () -> e.opts);
    }

    static void runAll() throws Exception {

        // ============================================================ pending: corruption + backup
        section("1.3.0 — Pendências: arquivo corrompido e backup");

        test("arquivo corrompido sem backup: vai para quarentena, é registrado e NUNCA vira 'lista vazia' calada", () -> {
            Path d = dir("corrupt1");
            List<String> msgs = new ArrayList<>();
            PendingStore ps = new PendingStore(d, msgs::add);
            ps.add("uuid:a", entry("r1", "Azul"));
            Path f = d.resolve("uuid_a.json");
            Files.writeString(f, "{ isto nao e json");
            ok(ps.list("uuid:a").isEmpty(), "nothing usable can be returned");
            ok(!ps.issues().isEmpty() && ps.issues().get(0).startsWith("CORRUPT"), "problem recorded: " + ps.issues());
            ok(!msgs.isEmpty(), "problem logged");
            List<Path> aside = new ArrayList<>();
            try (var s = Files.list(d)) { s.filter(p -> p.getFileName().toString().contains(".corrupt-")).forEach(aside::add); }
            eq(1, aside.size(), "the bad file is kept, not deleted");
            eq("{ isto nao e json", Files.readString(aside.get(0)), "content untouched");
        });

        test("arquivo corrompido COM backup: restaurado do backup, o ruim é guardado ao lado", () -> {
            Path d = dir("corrupt2");
            PendingStore ps = new PendingStore(d, m -> { });
            ps.add("uuid:a", entry("r1", "Azul"));
            ps.add("uuid:a", entry("r2", "Verde"));          // this write keeps the r1-only version as .bak
            Files.writeString(d.resolve("uuid_a.json"), "lixo");
            List<PendingStore.Entry> l = ps.list("uuid:a");
            eq(1, l.size(), "restored the last good backup");
            eq("r1", l.get(0).requestId(), "the backed-up entry");
            ok(ps.issues().get(0).startsWith("RESTORED"), "restore recorded: " + ps.issues());
            ok(Files.exists(d.resolve("uuid_a.json")), "main file is valid again");
        });

        test("falha ao gravar: add() avisa com exceção e nada é dado como guardado", () -> {
            Path d = dir("writefail");
            PendingStore ps = new PendingStore(d, m -> { });
            ps.add("uuid:a", entry("r1", "Azul"));
            blockWrites(d.resolve("uuid_a.json"));
            boolean threw = false;
            try { ps.add("uuid:a", entry("r2", "Verde")); } catch (java.io.IOException io) { threw = true; }
            ok(threw, "the caller is told the write failed");
            eq(1, ps.list("uuid:a").size(), "the failed entry was not counted");
        });

        test("recover() na inicialização conta restaurados, quarentenas e entregas interrompidas", () -> {
            Path d = dir("recover");
            PendingStore a = new PendingStore(d, m -> { });
            a.add("uuid:ok", entry("r1", "Azul"));
            a.add("uuid:bad", entry("r2", "Azul"));
            a.add("uuid:claiming", entry("r3", "Azul"));
            a.transition("uuid:claiming", "r3", State.CLAIMING, "x");
            Files.writeString(d.resolve("uuid_bad.json"), "lixo");
            PendingStore b = new PendingStore(d, m -> { });
            PendingStore.Report rep = b.recover();
            eq(3, rep.files(), "files checked");
            eq(1, rep.quarantined(), "quarantined");
            eq(1, rep.interrupted(), "interrupted claim reported");
            ok(b.issues().stream().anyMatch(i -> i.contains("interrupted")), "interrupted claim is an admin issue");
            ok(b.hasClaimable("uuid:ok"), "memory index built from disk");
            ok(!b.hasClaimable("uuid:claiming"), "an interrupted claim is NOT claimable");
        });

        // ============================================================ claim: idempotent, states, concurrency
        section("1.3.0 — Claim idempotente, estados e concorrência");

        test("estados persistem: PENDING -> CLAIMING -> removido só depois de entregar", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                e.game.full.add(p.uuid());
                eq(Status.KIT_PENDING, run(e, req("c-1", "Ana", Platform.JAVA, "Azul")).status(), "queued as pending (full)");
                PendingStore.Entry en = e.pending.list("uuid:" + p.uuid()).get(0);
                eq(State.PENDING, en.state(), "waiting");
                // the moment items reach the inventory, the entry must already be CLAIMING on disk
                AtomicInteger seen = new AtomicInteger();
                e.game.full.clear();
                e.game.onGive = () -> {
                    PendingStore fresh = new PendingStore(e.dir.resolve("pending"));
                    if (fresh.list("uuid:" + p.uuid()).get(0).state() == State.CLAIMING) seen.incrementAndGet();
                };
                eq(1, e.service.claim(p, Platform.JAVA), "delivered");
                eq(1, seen.get(), "CLAIMING was on disk BEFORE the items were given");
                eq(0, e.pending.list("uuid:" + p.uuid()).size(), "removed after delivery");
            }
        });

        test("claim interrompido (queda do servidor): reinício NÃO entrega de novo; admin decide", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                String key = "uuid:" + p.uuid();
                e.pending.add(key, entry("c-2", "Azul"));
                e.pending.transition(key, "c-2", State.CLAIMING, "claim started"); // ...and the server died here
                PendingStore reopened = new PendingStore(e.dir.resolve("pending"));
                reopened.recover();
                DeliveryService svc = newService(e, reopened);
                eq(0, svc.claim(p, Platform.JAVA), "not redelivered automatically");
                eq(0, e.game.given.size(), "nothing given");
                eq(1, svc.needsAttention().size(), "shown to the admin");
                ok(svc.resolve("c-2", false), "admin says: not delivered, retry");
                eq(1, svc.claim(p, Platform.JAVA), "now it is delivered");
                eq(1, e.game.given.size(), "exactly once");
            }
        });

        test("admin confirma 'delivered': fecha a pendência sem entregar de novo", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                String key = "uuid:" + p.uuid();
                e.pending.add(key, entry("c-3", "Azul"));
                e.pending.transition(key, "c-3", State.CLAIMING, "x");
                ok(e.service.resolve("c-3", true), "resolved");
                eq(0, e.pending.list(key).size(), "closed");
                eq(0, e.service.claim(p, Platform.JAVA), "nothing to give");
                eq(0, e.game.given.size(), "nothing given");
            }
        });

        test("entregou mas a remoção falhou: NÃO entrega a mesma Shulker Box outra vez", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                String key = "uuid:" + p.uuid();
                e.pending.add(key, entry("c-4", "Azul"));
                Path file = e.dir.resolve("pending").resolve("uuid_" + p.uuid() + ".json");
                e.game.onGive = () -> { try { blockWrites(file); } catch (Exception ex) { throw new RuntimeException(ex); } }; // disk dies after the items moved
                eq(1, e.service.claim(p, Platform.JAVA), "delivered once");
                e.game.onGive = null;
                eq(0, e.service.claim(p, Platform.JAVA), "the leftover entry is not given again");
                eq(1, e.game.given.size(), "still exactly one");
                unblockWrites(file);
                eq(0, e.service.claim(p, Platform.JAVA), "after the disk recovers: still not given again");
                eq(1, e.game.given.size(), "still exactly one");
                eq(0, e.pending.list(key).size(), "and the leftover entry was cleaned up");
            }
        });

        test("falha ao gravar CLAIMING: nada é entregue e a pendência continua", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                String key = "uuid:" + p.uuid();
                e.pending.add(key, entry("c-5", "Azul"));
                Path file = e.dir.resolve("pending").resolve("uuid_" + p.uuid() + ".json");
                blockWrites(file);
                eq(0, e.service.claim(p, Platform.JAVA), "not delivered");
                eq(0, e.game.given.size(), "nothing given when the state cannot be saved");
                unblockWrites(file);
                eq(1, e.service.claim(p, Platform.JAVA), "delivered once the disk works");
            }
        });

        test("pendência restaurada de um backup antigo não reentrega o que o histórico diz ter sido entregue", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                String key = "uuid:" + p.uuid();
                e.game.full.add(p.uuid());
                run(e, req("c-6", "Ana", Platform.JAVA, "Azul")); // KIT_PENDING
                e.game.full.clear();
                eq(1, e.service.claim(p, Platform.JAVA), "delivered");
                // a stale copy comes back (restored backup): the history says KIT_DELIVERED
                e.pending.add(key, entry("c-6", "Azul"));
                eq(0, e.service.claim(p, Platform.JAVA), "not given again");
                eq(1, e.game.given.size(), "exactly once");
            }
        });

        test("duas tentativas ao mesmo tempo (varias threads): cada kit sai uma única vez", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                String key = "uuid:" + p.uuid();
                for (int i = 0; i < 3; i++) e.pending.add(key, entry("par-" + i, "Azul"));
                ExecutorService pool = Executors.newFixedThreadPool(8);
                List<Future<Integer>> fs = new ArrayList<>();
                for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> e.service.claim(p, Platform.JAVA)));
                int total = 0;
                for (Future<Integer> f : fs) total += f.get(10, TimeUnit.SECONDS);
                pool.shutdownNow();
                eq(3, total, "three kits delivered in total");
                eq(3, e.game.given.size(), "no kit duplicated");
                eq(0, e.pending.list(key).size(), "queue empty");
            }
        });

        test("reinício com pendências: entregues ao entrar, uma vez", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                e.game.full.add(p.uuid());
                run(e, req("rs-1", "Ana", Platform.JAVA, "Azul"));
                e.game.full.clear();
                PendingStore reopened = new PendingStore(e.dir.resolve("pending"));
                reopened.recover();
                DeliveryService svc = newService(e, reopened);
                eq(1, svc.claim(p, Platform.JAVA), "delivered after the restart");
                eq(0, svc.claim(p, Platform.JAVA), "and not again");
                eq(1, e.game.given.size(), "once");
            }
        });

        // ============================================================ inventory full + auto-claim (service level)
        section("1.3.0 — Inventário cheio e auto-claim (lógica; os eventos do Bukkit precisam de teste no servidor)");

        test("inventário cheio: fica RETRY, avisa; liberou espaço -> próxima tentativa entrega; sem laço", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                String key = "uuid:" + p.uuid();
                e.game.full.add(p.uuid());
                run(e, req("ac-1", "Ana", Platform.JAVA, "Azul"));
                ok(e.service.hasClaimableByUuid(p.uuid()), "memory-only check says there is something to claim");
                DeliveryService.ClaimResult r1 = e.service.claimDetailed(p, Platform.JAVA, false);
                ok(r1.inventoryFull(), "still full");
                eq(State.RETRY, e.pending.list(key).get(0).state(), "RETRY, not lost");
                ok(e.service.hasClaimableByUuid(p.uuid()), "still claimable");
                long notices = e.game.told.stream().filter(m -> m.contains("kits pendentes")).count();
                eq(0L, notices, "an automatic attempt does not spam the player (the listener rate-limits the notice)");
                e.game.full.clear(); // the player freed a slot
                DeliveryService.ClaimResult r2 = e.service.claimDetailed(p, Platform.JAVA, false);
                eq(1, r2.delivered(), "delivered on the next attempt");
                ok(!e.service.hasClaimableByUuid(p.uuid()), "nothing left, so no more attempts are ever scheduled");
                ok(e.game.told.stream().anyMatch(m -> m.contains("Seu kit foi entregue com sucesso!")), "success message");
            }
        });

        test("/dk claim com inventário cheio mostra a mensagem pedida", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                e.game.full.add(p.uuid());
                run(e, req("ac-2", "Ana", Platform.JAVA, "Azul"));
                e.service.claimDetailed(p, Platform.JAVA, true);
                ok(e.game.told.contains("Ana:" + DeliveryService.PENDING_NOTICE), "told: " + e.game.told);
            }
        });

        // ============================================================ java/bedrock, offline, nick
        section("1.3.0 — Java/Bedrock, jogador offline e troca de nick");

        test("Java e Bedrock com nome parecido não são tratados como pedido duplicado", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                e.game.join("Steve", Platform.JAVA);
                e.game.join(".Steve", Platform.BEDROCK);
                Queue<Runnable> held = new ArrayDeque<>();
                e.main = held::add; // keep both requests in flight at the same time
                CompletableFuture<RequestRecord> a = e.service.submit(req("jb-1", "Steve", Platform.JAVA, "Azul"));
                CompletableFuture<RequestRecord> b = e.service.submit(req("jb-2", ".Steve", Platform.BEDROCK, "Azul"));
                e.main = Runnable::run;
                for (Runnable r : held) r.run();
                eq(Status.KIT_DELIVERED, a.get(5, TimeUnit.SECONDS).status(), "java");
                // (the fake game keeps one player per normalised name, so the bedrock request may fail for another
                // reason; what matters here is that it was NOT merged into the java one as a duplicate)
                ok(b.get(5, TimeUnit.SECONDS).status() != Status.DUPLICATE_REQUEST, "bedrock is not treated as a duplicate of java");
            }
        });

        test("offline (require-online=false): pendência por nome é ligada ao UUID ao entrar e entregue", () -> {
            try (Env e = new Env()) {
                e.opts = new DeliveryOptions(true, false, true, true, 5, ".");
                e.addKit("Azul");
                eq(Status.KIT_PENDING, run(e, req("off-1", "Bia", Platform.JAVA, "Azul")).status(), "queued for an offline player");
                PlayerRef bia = e.game.join("Bia", Platform.JAVA);
                eq(1, e.service.claim(bia, Platform.JAVA), "delivered on join");
                eq(0, e.pending.list("name:java:bia").size(), "name entry gone");
                eq(0, e.pending.list("uuid:" + bia.uuid()).size(), "uuid entry gone");
            }
        });

        test("offline: um jogador BEDROCK com o mesmo nome não recebe o kit pedido para o Java", () -> {
            try (Env e = new Env()) {
                e.opts = new DeliveryOptions(true, false, true, true, 5, ".");
                e.addKit("Azul");
                run(e, req("off-2", "Bia", Platform.JAVA, "Azul"));
                PlayerRef bedrock = e.game.join(".Bia", Platform.BEDROCK);
                eq(0, e.service.claim(bedrock, Platform.BEDROCK), "different platform: nothing");
                eq(0, e.game.given.size(), "not given");
                eq(1, e.pending.list("name:java:bia").size(), "still waiting for the right player");
            }
        });

        test("troca de nick: depois de ligada ao UUID, a pendência segue o jogador", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef old = e.game.join("NomeAntigo", Platform.JAVA);
                e.game.full.add(old.uuid());
                run(e, req("nick-1", "NomeAntigo", Platform.JAVA, "Azul"));
                e.game.full.clear();
                PlayerRef renamed = new PlayerRef(old.uuid(), "NomeNovo");
                e.game.online.put("nomenovo", renamed);
                eq(1, e.service.claim(renamed, Platform.JAVA), "found by UUID, not by the old nick");
            }
        });

        // ============================================================ status later + events
        section("1.3.0 — Atualização posterior de status (KIT_PENDING -> KIT_DELIVERED)");

        test("entrega a partir da pendência gera um evento idempotente ligado ao request_id", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                List<DeliveryEvent> events = new ArrayList<>();
                e.service.setEventSink(events::add);
                e.game.full.add(p.uuid());
                run(e, req("ev-1", "Ana", Platform.JAVA, "Azul"));
                eq(0, events.size(), "no event for the first answer (the result already says KIT_PENDING)");
                e.game.full.clear();
                e.service.claim(p, Platform.JAVA);
                eq(1, events.size(), "one event");
                eq("ev-1", events.get(0).requestId(), "tied to the original request");
                eq("ev-1:KIT_DELIVERED", events.get(0).eventId(), "deterministic id");
                eq("KIT_DELIVERED", events.get(0).toMap().get("status"), "status");
                e.service.claim(p, Platform.JAVA);
                eq(1, events.size(), "no second event, no second delivery");
                eq(Status.KIT_DELIVERED, e.store.get("ev-1").orElseThrow().status(), "history updated");
            }
        });

        test("/dk give (admin) não gera evento para o bot", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                PlayerRef p = e.game.join("Ana", Platform.JAVA);
                List<DeliveryEvent> events = new ArrayList<>();
                e.service.setEventSink(events::add);
                e.game.full.add(p.uuid());
                e.service.giveDirect(p, e.kits.get("Azul").orElseThrow(), "admin");
                e.game.full.clear();
                e.service.claim(p, Platform.JAVA);
                eq(0, events.size(), "admin deliveries are not purchases");
                eq(1, e.game.given.size(), "but the player still receives the kit");
            }
        });

        test("KIT_PENDING sem pendência guardada (ex.: backup antigo) aparece como órfão, não é reentregue", () -> {
            try (Env e = new Env()) {
                e.addKit("Azul");
                e.game.join("Ana", Platform.JAVA);
                e.game.full.add(e.game.online.get("ana").uuid());
                run(e, req("orf-1", "Ana", Platform.JAVA, "Azul"));
                Files.deleteIfExists(e.dir.resolve("pending").resolve("uuid_" + e.game.online.get("ana").uuid() + ".json"));
                eq(1, e.service.orphanedPending().size(), "reported");
                eq(0, e.game.given.size(), "nothing given automatically");
            }
        });

        // ============================================================ outbox
        section("1.3.0 — Outbox persistente");

        test("outbox: grava antes de enviar, sobrevive ao reinício e só some na confirmação", () -> {
            Path f = dir("outbox1").resolve("outbox.json");
            OutboxStore a = new OutboxStore(f, System::currentTimeMillis, m -> { });
            a.add("result:r1", OutboxStore.RESULT, "r1", "{\"request_id\":\"r1\"}");
            a.add("result:r1", OutboxStore.RESULT, "r1", "{\"request_id\":\"r1\"}"); // same id: one item
            eq(1, a.size(), "idempotent by id");
            OutboxStore b = new OutboxStore(f, System::currentTimeMillis, m -> { });
            eq(1, b.size(), "survived the restart");
            eq(1, b.due(10).size(), "due again right after a restart");
            b.confirm("result:r1");
            eq(0, new OutboxStore(f, System::currentTimeMillis, m -> { }).size(), "gone only after confirmation");
        });

        test("outbox corrompida: guardada ao lado, não apagada", () -> {
            Path f = dir("outbox2").resolve("outbox.json");
            Files.writeString(f, "xxx");
            List<String> msgs = new ArrayList<>();
            OutboxStore s = new OutboxStore(f, System::currentTimeMillis, msgs::add);
            eq(0, s.size(), "starts empty");
            ok(!msgs.isEmpty(), "reported");
            try (var l = Files.list(f.getParent())) { ok(l.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")), "bad file kept"); }
        });

        test("outbox: 401, 403, 500 e timeout NUNCA descartam o resultado; 400/404 viram 'recusado' guardado", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                long[] now = {1_000_000L};
                OutboxStore ob = new OutboxStore(null, () -> now[0], m -> { });
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, ob, PollClient.Options.classic(5, 5));
                for (int code : new int[]{401, 403, 500, 503, 429}) {
                    String id = "result:c" + code;
                    ob.add(id, OutboxStore.RESULT, "c" + code, "{\"request_id\":\"c" + code + "\",\"final\":true}");
                    bot.resultStatus.set(code);
                    now[0] += 3_600_000L;
                    pc.pollOnce();
                    waitFor(() -> ob.get(id).attempts >= 1, 5000, "attempt for " + code);
                    ok(ob.contains(id) && !ob.get(id).dead, code + " keeps the result and keeps retrying");
                    ob.confirm(id); // clear it so the next status code is tried on its own
                }
                ob.add("result:bad", OutboxStore.RESULT, "bad", "{\"request_id\":\"bad\"}");
                bot.resultStatus.set(400);
                now[0] += 3_600_000L;
                pc.pollOnce();
                waitFor(() -> ob.get("result:bad").dead, 5000, "400 becomes a dead letter");
                ok(ob.contains("result:bad"), "dead letters are kept for a person, not erased");
                eq(0, ob.size(), "and they are not retried");
                eq(1, ob.deadCount(), "counted");
            }
        });

        test("outbox: HTTP 200 sem {\"success\":true} não conta como confirmado", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                long[] now = {1_000_000L};
                OutboxStore ob = new OutboxStore(null, () -> now[0], m -> { });
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, ob, PollClient.Options.classic(5, 5));
                bot.resultStatus.set(200); // answers 200 {"success":false}
                ob.add("result:x", OutboxStore.RESULT, "x", "{\"request_id\":\"x\"}");
                pc.pollOnce();
                waitFor(() -> ob.get("result:x") != null && ob.get("result:x").attempts >= 1, 5000, "tried");
                ok(ob.contains("result:x"), "kept: 2xx alone is not a confirmation");
            }
        });

        test("eventos: o bot responde 404 (ainda sem o endpoint): evento fica guardado e é reenviado depois", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                long[] now = {1_000_000L};
                OutboxStore ob = new OutboxStore(null, () -> now[0], m -> { });
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, ob, PollClient.Options.classic(5, 5));
                bot.eventsStatus.set(404);
                DeliveryEvent ev = new DeliveryEvent("ev-9", "KIT_DELIVERED", "Ana", "Azul", 1L);
                pc.enqueueEvent(ev.eventId(), ev.requestId(), Json.write(ev.toMap()));
                waitFor(() -> ob.get("event:ev-9:KIT_DELIVERED") != null && ob.get("event:ev-9:KIT_DELIVERED").attempts == 1, 5000, "first try");
                ok(!ob.get("event:ev-9:KIT_DELIVERED").dead, "not discarded");
                bot.eventsStatus.set(0); // the bot got updated
                now[0] += 3_600_000L;
                pc.pollOnce();
                waitFor(() -> bot.eventBodies.size() == 1, 5000, "event delivered after the bot learned the endpoint");
                eq("ev-9:KIT_DELIVERED", Json.parseObject(bot.eventBodies.get(0)).get("event_id"), "event id");
                waitFor(() -> ob.size() == 0, 5000, "confirmed and removed");
            }
        });

        // ============================================================ long polling
        section("1.3.0 — Long Polling (contra um bot falso; o bot real é testado no teste ponta a ponta)");

        test("long polling: envia wait_ms, mantém UMA consulta, entrega assim que o pedido chega", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                e.addKit("Azul");
                e.game.join("Ana", Platform.JAVA);
                bot.holdMs = 400;
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, OutboxStore.inMemory(),
                        new PollClient.Options(5, 5, 35, true, 2, 1));
                pc.start();
                try {
                    waitFor(() -> bot.pulls.get() >= 2, 5000, "client keeps asking");
                    ok(bot.pullQueries.get(0).contains("wait_ms=2000"), "sends wait_ms: " + bot.pullQueries.get(0));
                    bot.queue.add(body("lp-1", "Ana", "java", "Azul"));
                    waitFor(() -> bot.resultBodies.size() == 1, 5000, "result");
                    eq("KIT_DELIVERED", Json.parseObject(bot.resultBodies.get(0)).get("status"), "delivered");
                    eq(1, bot.maxConcurrentPulls.get(), "never more than one pull in flight");
                } finally { pc.stop(); }
            }
        });

        test("long polling: http-timeout é sempre maior que a espera (35s com espera de 20s; corrige valor pequeno)", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                PollClient ok35 = new PollClient(bot.url(), TOKEN, e.service, e.log, OutboxStore.inMemory(), new PollClient.Options(5, 10, 35, true, 20, 3));
                ok(ok35.effectiveHttpTimeout().toSeconds() > ok35.waitSeconds(), "35s > 20s");
                PollClient small = new PollClient(bot.url(), TOKEN, e.service, e.log, OutboxStore.inMemory(), new PollClient.Options(5, 10, 5, true, 20, 3));
                ok(small.effectiveHttpTimeout().toSeconds() > small.waitSeconds(), "a too-small timeout is raised above the wait: " + small.effectiveHttpTimeout());
                PollClient capped = new PollClient(bot.url(), TOKEN, e.service, e.log, OutboxStore.inMemory(), new PollClient.Options(5, 10, 35, true, 99, 3));
                eq(25, capped.waitSeconds(), "the bot's limit of 25s is respected");
                PollClient off = new PollClient(bot.url(), TOKEN, e.service, e.log, OutboxStore.inMemory(), new PollClient.Options(5, 10, 10, false, 20, 3));
                eq(0, off.waitSeconds(), "long polling off: no wait_ms");
            }
        });

        test("long polling desligado: o modo tradicional continua sem wait_ms", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                e.addKit("Azul");
                e.game.join("Ana", Platform.JAVA);
                bot.queue.add(body("cl-1", "Ana", "java", "Azul"));
                new PollClient(bot.url(), TOKEN, e.service, e.log, OutboxStore.inMemory(), PollClient.Options.classic(5, 5)).pollOnce();
                waitFor(() -> bot.resultBodies.size() == 1, 5000, "delivered");
                ok(!bot.pullQueries.get(0).contains("wait_ms"), "no wait_ms in classic mode: " + bot.pullQueries.get(0));
            }
        });

        test("long polling: bot com erro 500 => espera progressiva, sem martelar", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                bot.pullStatus.set(500);
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, OutboxStore.inMemory(), new PollClient.Options(5, 5, 35, true, 2, 1));
                pc.start();
                try {
                    Thread.sleep(3500);
                    int n = bot.pulls.get();
                    ok(n >= 1 && n <= 4, "progressive backoff: " + n + " pulls in 3.5s (a tight loop would be hundreds)");
                } finally { pc.stop(); }
            }
        });

        test("long polling: bot que ignora wait_ms (responde 204 na hora) não vira laço apertado", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                bot.holdMs = 0; // answers 204 immediately
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, OutboxStore.inMemory(), new PollClient.Options(5, 5, 35, true, 20, 1));
                pc.start();
                try {
                    Thread.sleep(2500);
                    int n = bot.pulls.get();
                    ok(n <= 4, "paced by interval-seconds: " + n + " pulls in 2.5s");
                } finally { pc.stop(); }
            }
        });

        test("long polling: stop() cancela a consulta que estava esperando", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                bot.holdMs = 20_000;
                PollClient pc = new PollClient(bot.url(), TOKEN, e.service, e.log, OutboxStore.inMemory(), new PollClient.Options(5, 5, 35, true, 20, 1));
                pc.start();
                waitFor(() -> bot.pulls.get() == 1, 5000, "waiting pull");
                long t0 = System.currentTimeMillis();
                pc.stop();
                ok(System.currentTimeMillis() - t0 < 2000, "stop returns at once");
            }
        });

        test("token errado em long polling: nada é entregue e o erro de autenticação é registrado", () -> {
            try (Env e = new Env(); FakeBot bot = new FakeBot()) {
                e.addKit("Azul");
                e.game.join("Ana", Platform.JAVA);
                bot.queue.add(body("auth-1", "Ana", "java", "Azul"));
                PollClient pc = new PollClient(bot.url(), "w".repeat(40), e.service, e.log, OutboxStore.inMemory(), new PollClient.Options(5, 5, 35, true, 2, 1));
                pc.start();
                try {
                    waitFor(() -> bot.sawBadToken.get(), 5000, "bad token seen");
                    Thread.sleep(300);
                    eq(0, e.game.given.size(), "nothing delivered");
                } finally { pc.stop(); }
                e.log.close(); // the logger writes on its own thread; closing flushes it
                String logs;
                try (var w = Files.walk(e.dir.resolve("logs"))) {
                    logs = String.join("\n", w.filter(Files::isRegularFile).map(x -> { try { return Files.readString(x); } catch (Exception ex) { return ""; } }).toList());
                }
                ok(logs.contains("[AUTH_ERROR]"), "AUTH_ERROR logged");
            }
        });
    }
}
