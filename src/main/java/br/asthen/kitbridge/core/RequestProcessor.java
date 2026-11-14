package br.asthen.kitbridge.core;

import br.asthen.kitbridge.kit.KitData;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** What the transports (HTTP server, poll client) need from the plugin. Safe to call from any thread. */
public interface RequestProcessor {

    /** Completes with the final record. A repeated request_id completes with the original result. */
    CompletableFuture<RequestRecord> submit(KitRequest request);

    Optional<RequestRecord> status(String requestId);

    List<KitData> kits();
}
