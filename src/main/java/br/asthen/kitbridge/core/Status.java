package br.asthen.kitbridge.core;

/** Every state a request can be in. Non-final states are progress steps; final states end the request. */
public enum Status {
    // progress
    QUEUED(false, true, 202, "Request queued"),
    LOADING_PLAYER(false, true, 202, "Loading player"),
    PLAYER_ON(false, true, 202, "Player on"),
    LOADING_KIT(false, true, 202, "Loading kit"),
    DELIVERING_KIT(false, true, 202, "Delivering kit"),
    // success
    OK(true, true, 200, "OK"),
    KIT_DELIVERED(true, true, 200, "Kit delivered successfully"),
    KIT_PENDING(true, true, 200, "Kit stored as pending and will be delivered when the player can receive it"),
    // failures
    INVALID_REQUEST(true, false, 400, "Invalid request"),
    UNAUTHORIZED(true, false, 401, "Unauthorized"),
    NOT_FOUND(true, false, 404, "Not found"),
    METHOD_NOT_ALLOWED(true, false, 405, "Method not allowed"),
    PAYLOAD_TOO_LARGE(true, false, 413, "Payload too large"),
    RATE_LIMITED(true, false, 429, "Too many requests"),
    PLAYER_OFFLINE(true, false, 404, "Player not found"),
    PLATFORM_MISMATCH(true, false, 409, "Player found, but on a different platform than requested"),
    PLATFORM_UNVERIFIABLE(true, false, 409, "Player platform could not be verified (Floodgate not available)"),
    KIT_NOT_FOUND(true, false, 404, "Kit not found"),
    KIT_DISABLED(true, false, 409, "Kit is disabled"),
    KIT_CORRUPT(true, false, 500, "Kit data could not be loaded"),
    INVENTORY_FULL(true, false, 409, "Player inventory is full"),
    DELIVERY_DISABLED(true, false, 503, "Delivery from Discord is disabled"),
    DUPLICATE_REQUEST(true, false, 409, "An identical request for this player and kit is already in progress"),
    TIMEOUT(true, false, 504, "Request timed out"),
    INTERNAL_ERROR(true, false, 500, "Internal error");

    private final boolean terminal;
    private final boolean success;
    private final int http;
    private final String message;

    Status(boolean terminal, boolean success, int http, String message) {
        this.terminal = terminal;
        this.success = success;
        this.http = http;
        this.message = message;
    }

    public boolean isTerminal() { return terminal; }
    public boolean isSuccess() { return success; }
    public int http() { return http; }
    public String defaultMessage() { return message; }
}
