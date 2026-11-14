package br.asthen.kitbridge.core;

import br.asthen.kitbridge.core.GameAdapter.GiveResult;
import br.asthen.kitbridge.core.GameAdapter.Lookup;
import br.asthen.kitbridge.core.GameAdapter.Outcome;
import br.asthen.kitbridge.kit.KitData;
import br.asthen.kitbridge.kit.KitManager;
import br.asthen.kitbridge.kit.PendingStore;
import br.asthen.kitbridge.logs.DeliveryLogger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The delivery pipeline: validate -> find player -> load kit -> deliver, with idempotency,
 * a timeout, and a safe pending queue. All game access happens on {@code mainThread}.
 */
public final class DeliveryService implements RequestProcessor {

    private final GameAdapter game;
    private final KitManager kits;
    private final RequestStore store;
    private final PendingStore pending;
    private final DeliveryLogger log;
    private final Executor mainThread;
    private final Supplier<DeliveryOptions> options;

    private final Set<String> inFlightKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, CompletableFuture<RequestRecord>> running = new ConcurrentHashMap<>();

    public DeliveryService(GameAdapter game, KitManager kits, RequestStore store, PendingStore pending,
                           DeliveryLogger log, Executor mainThread, Supplier<DeliveryOptions> options) {
        this.game = game;
        this.kits = kits;
        this.store = store;
        this.pending = pending;
        this.log = log;
        this.mainThread = mainThread;
        this.options = options;
    }

    // ------------------------------------------------------------ RequestProcessor

    @Override
    public CompletableFuture<RequestRecord> submit(KitRequest req) {
        DeliveryOptions o = options.get();
        RequestStore.Registration reg = store.register(req);
        RequestRecord rec = reg.record();

        if (reg.duplicate()) {
            log.log("DUPLICATE", "request_id", req.requestId(), "player", req.player(), "kit", req.kit(),
                    "original_status", rec.status());
            CompletableFuture<RequestRecord> original = running.get(req.requestId());
            return original != null ? original : CompletableFuture.completedFuture(rec);
        }

        log.log("REQUEST_RECEIVED", "request_id", req.requestId(), "player", req.player(),
                "platform", req.platform().id(), "kit", req.kit());

        CompletableFuture<RequestRecord> future = new CompletableFuture<>();
        running.put(req.requestId(), future);
        future.whenComplete((r, t) -> running.remove(req.requestId()));

        String key = Names.normalize(req.player(), o.floodgatePrefix()) + "|" + req.kit().toLowerCase();
        if (o.preventDuplicate() && !inFlightKeys.add(key)) {
            finish(rec, Status.DUPLICATE_REQUEST, null, future, null);
            return future;
        }
        String heldKey = o.preventDuplicate() ? key : null;

        AtomicBoolean started = new AtomicBoolean();
        try {
            mainThread.execute(() -> {
                if (!started.compareAndSet(false, true)) return; // the timeout got there first
                try {
                    process(rec, o, future, heldKey);
                } catch (Throwable t) {
                    finish(rec, Status.INTERNAL_ERROR, "Internal error while delivering the kit", future, heldKey);
                    log.log("ERROR", "request_id", rec.requestId(), "error", t);
                }
            });
        } catch (RuntimeException rejected) {
            if (started.compareAndSet(false, true)) {
                finish(rec, Status.INTERNAL_ERROR, "Server is shutting down", future, heldKey);
            }
            return future;
        }

        CompletableFuture.delayedExecutor(Math.max(1, o.requestTimeoutSeconds()), TimeUnit.SECONDS).execute(() -> {
            if (started.compareAndSet(false, true)) {
                finish(rec, Status.TIMEOUT, null, future, heldKey);
            }
        });
        return future;
    }

    @Override
    public Optional<RequestRecord> status(String requestId) {
        return store.get(requestId);
    }

    @Override
    public List<KitData> kits() {
        return kits.list();
    }

    // ------------------------------------------------------------ pipeline (main thread)

    private void process(RequestRecord rec, DeliveryOptions o, CompletableFuture<RequestRecord> future, String heldKey) {
        if (!o.allowDiscordDelivery()) {
            finish(rec, Status.DELIVERY_DISABLED, null, future, heldKey);
            return;
        }

        store.update(rec, Status.LOADING_PLAYER, null);
        Lookup lk = game.find(rec.player(), rec.platform());
        switch (lk.outcome()) {
            case MISMATCH -> {
                finish(rec, Status.PLATFORM_MISMATCH, null, future, heldKey);
                return;
            }
            case UNVERIFIABLE -> {
                finish(rec, Status.PLATFORM_UNVERIFIABLE, null, future, heldKey);
                return;
            }
            case OFFLINE -> {
                if (o.requireOnline()) {
                    finish(rec, Status.PLAYER_OFFLINE, null, future, heldKey);
                    return;
                }
            }
            case ONLINE -> {
                store.update(rec, Status.PLAYER_ON, null);
                log.log("PLAYER_IDENTIFIED", "request_id", rec.requestId(), "player", lk.player().name(),
                        "platform", rec.platform().id());
            }
        }

        store.update(rec, Status.LOADING_KIT, null);
        Optional<KitData> found = kits.get(rec.kit());
        if (found.isEmpty()) {
            finish(rec, Status.KIT_NOT_FOUND, null, future, heldKey);
            return;
        }
        KitData kit = found.get();
        if (!kit.enabled()) {
            finish(rec, Status.KIT_DISABLED, null, future, heldKey);
            return;
        }

        if (lk.outcome() == Outcome.OFFLINE) { // only reachable when require-online is false
            String key = nameKey(rec.platform(), rec.player(), o.floodgatePrefix());
            if (queue(key, rec, kit, future, heldKey)) {
                finish(rec, Status.KIT_PENDING, "Player is offline: the kit will be delivered when they join", future, heldKey);
            }
            return;
        }

        PlayerRef player = lk.player();
        store.update(rec, Status.DELIVERING_KIT, null);
        GiveResult res = game.give(player, kit);
        switch (res) {
            case DELIVERED -> {
                game.tell(player, "Você recebeu o kit " + kit.name() + "!");
                finish(rec, Status.KIT_DELIVERED, null, future, heldKey);
            }
            case FULL -> {
                if (o.pendingIfInventoryFull()) {
                    if (queue("uuid:" + player.uuid(), rec, kit, future, heldKey)) {
                        game.tell(player, "Seu inventário está cheio! O kit " + kit.name()
                                + " ficou guardado. Libere espaço e use /dk claim para recebê-lo.");
                        finish(rec, Status.KIT_PENDING, "Inventory full: kit stored as pending; the player can claim it with /dk claim", future, heldKey);
                    }
                } else {
                    game.tell(player, "Seu inventário está cheio! Não foi possível entregar o kit " + kit.name() + ".");
                    finish(rec, Status.INVENTORY_FULL, null, future, heldKey);
                }
            }
            case CORRUPT -> finish(rec, Status.KIT_CORRUPT, null, future, heldKey);
            case OFFLINE -> finish(rec, Status.PLAYER_OFFLINE, null, future, heldKey);
        }
    }

    /** @return false when saving failed (the request is already finished as INTERNAL_ERROR) */
    private boolean queue(String key, RequestRecord rec, KitData kit, CompletableFuture<RequestRecord> future, String heldKey) {
        try {
            pending.add(key, new PendingStore.Entry(rec.requestId(), kit.name(), System.currentTimeMillis(), kit));
            return true;
        } catch (IOException e) {
            finish(rec, Status.INTERNAL_ERROR, "Could not store the pending kit", future, heldKey);
            log.log("ERROR", "request_id", rec.requestId(), "error", e.getMessage());
            return false;
        }
    }

    private void finish(RequestRecord rec, Status status, String message, CompletableFuture<RequestRecord> future, String heldKey) {
        store.update(rec, status, message);
        if (heldKey != null) inFlightKeys.remove(heldKey);
        if (status == Status.KIT_DELIVERED || status == Status.KIT_PENDING) {
            log.log(status == Status.KIT_DELIVERED ? "DELIVERED" : "PENDING",
                    "request_id", rec.requestId(), "player", rec.player(), "platform", rec.platform().id(), "kit", rec.kit());
        } else if (status == Status.DUPLICATE_REQUEST) {
            log.log("DUPLICATE", "request_id", rec.requestId(), "player", rec.player(), "kit", rec.kit(), "reason", "in-flight");
        } else {
            log.log("FAILED", "request_id", rec.requestId(), "player", rec.player(), "platform", rec.platform().id(),
                    "kit", rec.kit(), "status", status.name());
        }
        future.complete(rec);
    }

    // ------------------------------------------------------------ direct give by an admin (main thread)

    public enum DirectOutcome { DELIVERED, PENDING, FULL, CORRUPT, OFFLINE, ERROR }

    /**
     * Gives a kit straight to a player on behalf of an admin (/dk give). Same rules as a Discord
     * delivery: all or nothing, and a full inventory parks the whole kit as pending (when enabled).
     */
    public DirectOutcome giveDirect(PlayerRef player, KitData kit, String by) {
        GiveResult r = game.give(player, kit);
        switch (r) {
            case DELIVERED -> {
                game.tell(player, "Você recebeu o kit " + kit.name() + "!");
                log.log("ADMIN", "action", "give", "by", by, "player", player.name(), "kit", kit.name(), "result", "delivered");
                return DirectOutcome.DELIVERED;
            }
            case FULL -> {
                if (!options.get().pendingIfInventoryFull()) {
                    game.tell(player, "Seu inventário está cheio! Não foi possível entregar o kit " + kit.name() + ".");
                    return DirectOutcome.FULL;
                }
                try {
                    pending.add("uuid:" + player.uuid(), new PendingStore.Entry("admin-" + java.util.UUID.randomUUID(),
                            kit.name(), System.currentTimeMillis(), kit));
                } catch (IOException e) {
                    log.log("ERROR", "action", "give", "error", e.getMessage());
                    return DirectOutcome.ERROR;
                }
                game.tell(player, "Seu inventário está cheio! O kit " + kit.name()
                        + " ficou guardado. Libere espaço e use /dk claim para recebê-lo.");
                log.log("ADMIN", "action", "give", "by", by, "player", player.name(), "kit", kit.name(), "result", "pending");
                return DirectOutcome.PENDING;
            }
            case CORRUPT -> {
                return DirectOutcome.CORRUPT;
            }
            default -> {
                return DirectOutcome.OFFLINE;
            }
        }
    }

    // ------------------------------------------------------------ pending claims (main thread)

    /**
     * Tries to deliver everything waiting for this player. Runs on the join event and on /dk claim.
     * Each kit is delivered whole or left in the queue.
     *
     * @param actual the player's real platform, or null when unknown
     * @return number of kits delivered
     */
    public int claim(PlayerRef player, Platform actual) {
        DeliveryOptions o = options.get();
        int delivered = 0;
        List<String> keys = actual == null
                ? List.of("uuid:" + player.uuid())
                : List.of("uuid:" + player.uuid(), nameKey(actual, player.name(), o.floodgatePrefix()));
        for (String key : keys) {
            for (PendingStore.Entry e : pending.list(key)) {
                GiveResult r = game.give(player, e.kit());
                if (r == GiveResult.DELIVERED) {
                    try {
                        pending.remove(key, e.requestId());
                    } catch (IOException ex) {
                        log.log("ERROR", "request_id", e.requestId(), "error", ex.getMessage());
                        return delivered; // keep the entry rather than risk delivering it twice later
                    }
                    delivered++;
                    game.tell(player, "Você recebeu o kit " + e.kitName() + "!");
                    store.get(e.requestId()).ifPresent(rec ->
                            store.update(rec, Status.KIT_DELIVERED, "Kit delivered successfully (from pending)"));
                    log.log("DELIVERED", "request_id", e.requestId(), "player", player.name(), "kit", e.kitName(), "via", "pending");
                } else if (r == GiveResult.FULL) {
                    game.tell(player, "Seu inventário ainda está cheio. Libere espaço e use /dk claim.");
                    return delivered;
                } else if (r == GiveResult.CORRUPT) {
                    log.log("FAILED", "request_id", e.requestId(), "player", player.name(), "kit", e.kitName(), "status", "KIT_CORRUPT");
                }
            }
        }
        return delivered;
    }

    /** Number of kits waiting for this player. */
    public int pendingCount(PlayerRef player, Platform actual) {
        DeliveryOptions o = options.get();
        int n = pending.count("uuid:" + player.uuid());
        if (actual != null) n += pending.count(nameKey(actual, player.name(), o.floodgatePrefix()));
        return n;
    }

    private static String nameKey(Platform p, String name, String prefix) {
        return "name:" + p.id() + ":" + Names.normalize(name, prefix);
    }
}
