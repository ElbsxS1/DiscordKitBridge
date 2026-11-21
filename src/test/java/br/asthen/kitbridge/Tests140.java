package br.asthen.kitbridge;

import br.asthen.kitbridge.api.ResponseHandler;
import br.asthen.kitbridge.core.*;
import br.asthen.kitbridge.kit.KitData;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static br.asthen.kitbridge.TestMain.*;

/**
 * Tests added for 1.4.0 (Kit Inicial). Same rules as TestMain: JDK only. They prove the LOGIC that keeps the Kit Inicial
 * away from the Discord bot and the cooldown/world rules; the Paper side (events, inventory) is in TESTING.md.
 */
final class Tests140 {

    private Tests140() {
    }

    static void runAll() throws Exception {

        section("1.4.0 — Kit Inicial: nome reservado e isolamento do bot");

        test("isStarter ignora maiúsculas/minúsculas e espaços; outros nomes não casam", () -> {
            ok(StarterKitPolicy.isStarter("Kit_Inicial", "Kit_Inicial"), "mesmo nome");
            ok(StarterKitPolicy.isStarter("kit_inicial", "Kit_Inicial"), "minúsculas");
            ok(StarterKitPolicy.isStarter(" KIT_INICIAL ", "Kit_Inicial"), "espaços");
            ok(!StarterKitPolicy.isStarter("Kit_Dima", "Kit_Inicial"), "outro kit");
            ok(!StarterKitPolicy.isStarter("Kit_Inicial2", "Kit_Inicial"), "prefixo não basta");
            ok(!StarterKitPolicy.isStarter(null, "Kit_Inicial"), "null");
            ok(StarterKitPolicy.isStarter("Kit_Inicial", null), "sem nome configurado usa o padrão");
            ok(StarterKitPolicy.isStarter("Kit_Inicial", " "), "nome em branco usa o padrão");
            ok(StarterKitPolicy.isStarter("Novato", "Novato"), "nome personalizado");
        });

        test("a lista de kits enviada ao bot NÃO contém o Kit Inicial (nem na forma de JSON)", () -> {
            try (Env e = new Env()) {
                e.addKit("Kit_Inicial");
                e.addKit("Kit_Dima");
                List<String> names = e.service.kits().stream().map(KitData::name).toList();
                eq(List.of("Kit_Dima"), names, "kits() para o bot");
                String json = ResponseHandler.kits(e.service.kits()).body();
                ok(!json.contains("Kit_Inicial"), "o JSON da sincronização não cita o Kit Inicial");
                ok(json.contains("Kit_Dima"), "kits normais continuam na lista");
                // o plugin continua enxergando o kit (é dele que a entrega automática lê)
                ok(e.kits.get("kit_inicial").isPresent(), "KitManager ainda tem o Kit Inicial");
            }
        });

        test("pedido do Discord pelo Kit Inicial: KIT_NOT_FOUND, nada entregue, nada na fila de pendências", () -> {
            try (Env e = new Env()) {
                e.addKit("Kit_Inicial");
                e.game.join("Steve", Platform.JAVA);
                RequestRecord r = run(e, req("r-starter-1", "Steve", Platform.JAVA, "Kit_Inicial"));
                eq(Status.KIT_NOT_FOUND, r.status(), "status igual ao de um kit inexistente");
                eq(0, e.game.given.size(), "nada entregue");
                eq(0, e.pending.keys().size(), "nada pendente");
                RequestRecord r2 = run(e, req("r-starter-2", "Steve", Platform.JAVA, "kit_inicial"));
                eq(Status.KIT_NOT_FOUND, r2.status(), "minúsculas também recusa");
                eq(0, e.game.given.size(), "continua sem entrega");
            }
        });

        test("recusa mesmo com jogador offline e require-online=false (não vira pendência)", () -> {
            try (Env e = new Env()) {
                e.opts = new DeliveryOptions(true, false, true, true, 5, ".");
                e.addKit("Kit_Inicial");
                RequestRecord r = run(e, req("r-starter-3", "Offline", Platform.JAVA, "Kit_Inicial"));
                eq(Status.KIT_NOT_FOUND, r.status(), "status");
                eq(0, e.pending.keys().size(), "nenhuma pendência criada");
            }
        });

        test("o nome reservado vem das opções: kit com outro nome continua normal, o novo nome fica oculto", () -> {
            try (Env e = new Env()) {
                e.opts = new DeliveryOptions(true, true, true, true, 5, ".", "Novato");
                e.addKit("Kit_Inicial");
                e.addKit("Novato");
                e.game.join("Alex", Platform.JAVA);
                eq(List.of("Kit_Inicial"), e.service.kits().stream().map(KitData::name).toList(), "só o Novato é oculto");
                eq(Status.KIT_NOT_FOUND, run(e, req("r-n1", "Alex", Platform.JAVA, "Novato")).status(), "Novato recusado");
                eq(Status.KIT_DELIVERED, run(e, req("r-n2", "Alex", Platform.JAVA, "Kit_Inicial")).status(), "Kit_Inicial agora é um kit comum");
            }
        });

        test("regressão: kits comuns seguem sendo entregues normalmente", () -> {
            try (Env e = new Env()) {
                e.addKit("Kit_Dima");
                e.game.join("Steve", Platform.JAVA);
                eq(Status.KIT_DELIVERED, run(e, req("r-ok", "Steve", Platform.JAVA, "Kit_Dima")).status(), "entregue");
                eq(List.of("Steve:Kit_Dima"), List.copyOf(e.game.given), "entregue uma vez");
            }
        });

        section("1.4.0 — Kit Inicial: cooldown, mundos e configuração");

        test("cooldown: segunda entrega dentro do prazo é barrada, depois do prazo passa", () -> {
            StarterKitPolicy p = new StarterKitPolicy();
            UUID id = UUID.randomUUID();
            ok(p.tryAcquire(id, 1_000, 10_000), "primeira passa");
            ok(!p.tryAcquire(id, 5_000, 10_000), "5 s depois: barrada");
            ok(!p.tryAcquire(id, 10_999, 10_000), "quase 10 s: barrada");
            ok(p.tryAcquire(id, 11_000, 10_000), "10 s depois: passa");
            ok(!p.tryAcquire(id, 12_000, 10_000), "o prazo recomeçou");
        });

        test("cooldown é por jogador, 0 desliga, release devolve, relógio voltando não trava", () -> {
            StarterKitPolicy p = new StarterKitPolicy();
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            ok(p.tryAcquire(a, 1_000, 10_000), "a");
            ok(p.tryAcquire(b, 1_000, 10_000), "b não é afetado por a");
            ok(p.tryAcquire(a, 1_001, 0), "cooldown 0 sempre passa");
            ok(!p.tryAcquire(a, 1_500, 10_000), "barrada");
            p.release(a);
            ok(p.tryAcquire(a, 1_600, 10_000), "release devolveu o prazo");
            ok(p.tryAcquire(a, 100, 10_000), "relógio andou para trás: não trava o jogador");
        });

        test("cooldown: várias threads ao mesmo tempo, só UMA entrega passa", () -> {
            StarterKitPolicy p = new StarterKitPolicy();
            UUID id = UUID.randomUUID();
            java.util.concurrent.atomic.AtomicInteger passed = new java.util.concurrent.atomic.AtomicInteger();
            List<Thread> ts = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                Thread t = new Thread(() -> { if (p.tryAcquire(id, 1_000, 10_000)) passed.incrementAndGet(); });
                ts.add(t);
                t.start();
            }
            for (Thread t : ts) t.join();
            eq(1, passed.get(), "entregas que passaram");
        });

        test("inWorlds compara sem diferenciar maiúsculas", () -> {
            ok(StarterKitPolicy.inWorlds("Lobby", Set.of("lobby")), "Lobby x lobby");
            ok(!StarterKitPolicy.inWorlds("survival", Set.of("lobby")), "survival fora");
            ok(!StarterKitPolicy.inWorlds("survival", Set.of()), "lista vazia");
            ok(!StarterKitPolicy.inWorlds(null, Set.of("lobby")), "null");
        });

        test("matchesEnter: só lobby -> survival (o aldeão); não dispara no sentido contrário nem entre outros mundos", () -> {
            StarterKitSettings s = new StarterKitSettings(true, "Kit_Inicial", 10, true, Set.of("lobby"), true, true,
                    "lobby", "survival", true, "");
            ok(s.matchesEnter("lobby", "survival"), "lobby -> survival");
            ok(s.matchesEnter("Lobby", "SURVIVAL"), "ignora maiúsculas");
            ok(!s.matchesEnter("survival", "lobby"), "survival -> lobby (/lob) não dá kit");
            ok(!s.matchesEnter("survival_nether", "survival"), "nether -> survival não dá kit");
            ok(!s.matchesEnter("lobby", "survival_nether"), "lobby -> nether não dá kit");
            StarterKitSettings off = new StarterKitSettings(true, "Kit_Inicial", 10, true, Set.of(), true, false,
                    "lobby", "survival", true, "");
            ok(!off.matchesEnter("lobby", "survival"), "on-enter-world desligado");
            StarterKitSettings blank = new StarterKitSettings(true, "Kit_Inicial", 10, true, Set.of(), true, true,
                    "", "", true, "");
            ok(!blank.matchesEnter("", ""), "mundos em branco nunca casam");
        });

        test("StarterKitSettings normaliza valores ruins sem quebrar", () -> {
            StarterKitSettings s = new StarterKitSettings(true, "  ", -5, true, Set.of(), true, true, null, null, true, null);
            eq("Kit_Inicial", s.name(), "nome padrão");
            eq(0, s.cooldownSeconds(), "cooldown negativo vira 0");
            eq("", s.message(), "mensagem nula vira vazia");
        });

        test("Kit_Inicial é um nome de kit válido (/dk create Kit_Inicial)", () -> {
            ok(br.asthen.kitbridge.security.Validators.isValidKitName("Kit_Inicial"), "nome válido");
        });
    }
}
