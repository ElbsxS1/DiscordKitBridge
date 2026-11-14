package br.asthen.kitbridge.core;

import br.asthen.kitbridge.security.Validators;

import java.util.Map;

/** A validated delivery request. Instances can only be built through {@link #parse}. */
public record KitRequest(String requestId, String player, Platform platform, String kit) {

    public static final class InvalidRequestException extends Exception {
        private final String requestId;

        public InvalidRequestException(String message, String requestId) {
            super(message);
            this.requestId = requestId;
        }

        /** The request id if it was valid, otherwise null. */
        public String requestId() { return requestId; }
    }

    public static KitRequest parse(Map<String, Object> body) throws InvalidRequestException {
        String id = str(body, "request_id");
        String safeId = Validators.isValidRequestId(id) ? id : null;
        if (safeId == null) throw new InvalidRequestException("request_id is required (1-64 chars: letters, digits, _ . : -)", null);
        String player = str(body, "player");
        if (!Validators.isValidPlayerName(player)) throw new InvalidRequestException("player is invalid", safeId);
        Platform platform = Platform.parse(str(body, "platform"));
        if (platform == null) throw new InvalidRequestException("platform must be \"bedrock\" or \"java\"", safeId);
        String kit = str(body, "kit");
        if (!Validators.isValidKitName(kit)) throw new InvalidRequestException("kit is invalid", safeId);
        return new KitRequest(safeId, player.strip(), platform, kit);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String s ? s : null;
    }
}
