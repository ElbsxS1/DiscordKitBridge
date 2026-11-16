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
import java.util.function.Consumer;
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
    /** Players with a claim running right now (one claim per player at a time). */
    private final Set<String> claimLocks = ConcurrentHashMap.newKeySet();
    /** Delivered in the game, but removing the pending entry from disk failed: never give these again. */
    private final Set<String> deliveredNotPersisted = ConcurrentHashMap.newKeySet();
    private volatile Consumer<DeliveryEvent> eventSink = e -> { };
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

        String key = req.platform().id() + "|" + Names.normalize(req.player(), o.floodgatePrefix()) + "|" + req.kit().toLowerCase();
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

    /** Where delivery updates after the first answer go (the outbox). Never null. */
    public void setEventSink(Consumer<DeliveryEvent> sink) {
        this.eventSink = sink != null ? sink : e -> { };
    }

    public static final String PENDING_NOTICE = "Você possui kits pendentes! Libere espaço no inventário para receber suas compras.";

    /** Result of one claim run. */
    public record ClaimResult(int delivered, boolean inventoryFull, int stillPending) {
    }

    /** @return number of kits delivered (kept for callers that only need the count) */
    public int claim(PlayerRef player, Platform actual) {
        return claimDetailed(player, actual).delivered();
    }

    /**
     * Tries to deliver everything waiting for this player. Runs on join, on /dk claim and from auto-claim.
     * Each kit is delivered whole or left in the queue.
     *
     * <p>Crash safety: the entry is persisted as CLAIMING before any item moves and removed only after the
     * delivery. A crash in between leaves CLAIMING, which is never retried automatically (the kit may or may
     * not have reached the inventory), so a kit is never given twice without a person deciding so.
     *
     * @param actual the player's real platform, or null when unknown
     */
    public ClaimResult claimDetailed(PlayerRef player, Platform actual) {
        return claimDetailed(player, actual, true);
    }

    /** @param tellFull false for automatic attempts: the caller rate-limits the "inventory full" notice itself */
    public ClaimResult claimDetailed(PlayerRef player, Platform actual, boolean tellFull) {
        DeliveryOptions o = options.get();
        String uuidKey = "uuid:" + player.uuid();
        if (!claimLocks.add(uuidKey)) return new ClaimResult(0, false, pendingCount(player, actual)); // already running
        int delivered = 0;
        boolean full = false;
        try {
            retryUnpersistedRemovals();
            // the player's identity is now known: file name-based entries under the UUID
            if (actual != null) {
                try {
                    pending.moveAll(nameKey(actual, player.name(), o.floodgatePrefix()), uuidKey);
                } catch (IOException ex) {
                    log.log("ERROR", "player", player.name(), "error", "could not file pending kits under the UUID: " + ex.getMessage());
                }
            }
            List<String> keys = actual == null
                    ? List.of(uuidKey)
                    : List.of(uuidKey, nameKey(actual, player.name(), o.floodgatePrefix())); // name key kept if moveAll failed
            outer:
            for (String key : keys) {
                for (PendingStore.Entry e : pending.claimable(key)) {
                    if (alreadyDelivered(key, e)) continue;
                    try {
                        pending.transition(key, e.requestId(), PendingStore.State.CLAIMING, "claim started");
                    } catch (IOException ex) {
                        log.log("ERROR", "request_id", e.requestId(), "error", "could not mark CLAIMING, nothing was given: " + ex.getMessage());
                        break outer;
                    }
                    GiveResult r = game.give(player, e.kit());
                    if (r == GiveResult.DELIVERED) {
                        delivered++;
                        afterDelivered(key, e, player);
                    } else if (r == GiveResult.FULL) {
                        safeTransition(key, e, PendingStore.State.RETRY, "inventory full");
                        full = true;
                        if (tellFull) game.tell(player, PENDING_NOTICE);
                        break outer;
                    } else if (r == GiveResult.CORRUPT) {
                        safeTransition(key, e, PendingStore.State.ERROR, "kit data could not be loaded");
                        log.log("FAILED", "request_id", e.requestId(), "player", player.name(), "kit", e.kitName(), "status", "KIT_CORRUPT");
                    } else { // OFFLINE: the player left between the check and the give; nothing was given
                        safeTransition(key, e, PendingStore.State.RETRY, "player went offline");
                        break outer;
                    }
                }
            }
        } finally {
            claimLocks.remove(uuidKey);
        }
        return new ClaimResult(delivered, full, pendingCount(player, actual));
    }

    /** If the request history already says DELIVERED, the entry is a leftover (e.g. restored from a backup). */
    private boolean alreadyDelivered(String key, PendingStore.Entry e) {
        if (deliveredNotPersisted.contains(e.requestId())) return true;
        boolean done = store.get(e.requestId()).map(r -> r.status() == Status.KIT_DELIVERED).orElse(false);
        if (done) {
            try {
                pending.remove(key, e.requestId());
                log.log("RECONCILED", "request_id", e.requestId(), "action", "dropped a leftover pending entry: already delivered");
            } catch (IOException ex) {
                deliveredNotPersisted.add(e.requestId());
                log.log("ERROR", "request_id", e.requestId(), "error", ex.getMessage());
            }
        }
        return done;
    }

    private void afterDelivered(String key, PendingStore.Entry e, PlayerRef player) {
        try {
            pending.remove(key, e.requestId());
        } catch (IOException ex) {
            // the kit IS in the inventory: remember that in memory so it is never given again, and retry the cleanup
            deliveredNotPersisted.add(e.requestId());
            log.log("ERROR", "request_id", e.requestId(), "error",
                    "delivered, but removing the pending entry failed (" + ex.getMessage() + "); it will not be given again");
        }
        game.tell(player, "Seu kit foi entregue com sucesso!");
        store.get(e.requestId()).ifPresent(rec ->
                store.update(rec, Status.KIT_DELIVERED, "Kit delivered successfully (from pending)"));
        log.log("DELIVERED", "request_id", e.requestId(), "player", player.name(), "kit", e.kitName(), "via", "pending");
        if (!e.requestId().startsWith("admin-")) {
            eventSink.accept(new DeliveryEvent(e.requestId(), Status.KIT_DELIVERED.name(), player.name(), e.kitName(),
                    System.currentTimeMillis()));
        }
    }

    private void safeTransition(String key, PendingStore.Entry e, PendingStore.State to, String note) {
        try {
            pending.transition(key, e.requestId(), to, note);
        } catch (IOException ex) {
            log.log("ERROR", "request_id", e.requestId(), "error", "could not mark " + to + ": " + ex.getMessage());
        }
    }

    private void retryUnpersistedRemovals() {
        if (deliveredNotPersisted.isEmpty()) return;
        for (String id : List.copyOf(deliveredNotPersisted)) {
            for (String key : pending.keys()) {
                for (PendingStore.Entry e : pending.list(key)) {
                    if (e.requestId().equals(id)) {
                        try {
                            pending.remove(key, id);
                            deliveredNotPersisted.remove(id);
                        } catch (IOException ex) {
                            // still failing: keep blocking redelivery
                        }
                    }
                }
            }
        }
    }

    /**
     * Admin decision for an entry stuck in CLAIMING or ERROR (see {@code /dk pending resolve}).
     *
     * @param delivered true = the player did get the kit (close it), false = they did not (try again)
     * @return false when no such entry exists
     */
    public boolean resolve(String requestId, boolean delivered) throws IOException {
        for (String key : pending.keys()) {
            for (PendingStore.Entry e : pending.list(key)) {
                if (!e.requestId().equals(requestId)) continue;
                if (delivered) {
                    pending.remove(key, requestId);
                    store.get(requestId).ifPresent(rec -> store.update(rec, Status.KIT_DELIVERED, "Confirmed delivered by an administrator"));
                    if (!requestId.startsWith("admin-")) {
                        eventSink.accept(new DeliveryEvent(requestId, Status.KIT_DELIVERED.name(), null, e.kitName(), System.currentTimeMillis()));
                    }
                    log.log("ADMIN", "action", "resolve", "request_id", requestId, "result", "delivered");
                } else {
                    pending.transition(key, requestId, PendingStore.State.RETRY, "admin: not delivered, retry");
                    log.log("ADMIN", "action", "resolve", "request_id", requestId, "result", "retry");
                }
                return true;
            }
        }
        return false;
    }

    /** Unresolved entries that need a person (CLAIMING or ERROR), for /dk pending. */
    public List<PendingStore.Entry> needsAttention() {
        List<PendingStore.Entry> out = new java.util.ArrayList<>();
        for (String key : pending.keys()) {
            for (PendingStore.Entry e : pending.list(key)) {
                if (e.state() == PendingStore.State.CLAIMING || e.state() == PendingStore.State.ERROR) out.add(e);
            }
        }
        return out;
    }

    /**
     * Safety net: requests the bot was told are KIT_PENDING but that have no pending entry any more (for example
     * after a pending file had to be restored from an older backup). They are not redelivered automatically.
     */
    public List<RequestRecord> orphanedPending() {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (String key : pending.keys()) for (PendingStore.Entry e : pending.list(key)) known.add(e.requestId());
        known.addAll(deliveredNotPersisted);
        List<RequestRecord> out = new java.util.ArrayList<>();
        for (RequestRecord r : store.all()) {
            if (r.status() == Status.KIT_PENDING && !known.contains(r.requestId())) out.add(r);
        }
        return out;
    }

    /** Number of kits waiting for this player (any unresolved state). */
    public int pendingCount(PlayerRef player, Platform actual) {
        DeliveryOptions o = options.get();
        int n = pending.count("uuid:" + player.uuid());
        if (actual != null) n += pending.count(nameKey(actual, player.name(), o.floodgatePrefix()));
        return n;
    }

    /** Memory-only and cheapest: anything claimable filed under this UUID? Safe on every inventory event. */
    public boolean hasClaimableByUuid(java.util.UUID uuid) {
        return pending.hasClaimable("uuid:" + uuid);
    }

    /** Memory-only: is there something this player could receive right now? Safe on every inventory event. */
    public boolean hasClaimable(PlayerRef player, Platform actual) {
        DeliveryOptions o = options.get();
        if (pending.hasClaimable("uuid:" + player.uuid())) return true;
        return actual != null && pending.hasClaimable(nameKey(actual, player.name(), o.floodgatePrefix()));
    }

    private static String nameKey(Platform p, String name, String prefix) {
        return "name:" + p.id() + ":" + Names.normalize(name, prefix);
    }
}
