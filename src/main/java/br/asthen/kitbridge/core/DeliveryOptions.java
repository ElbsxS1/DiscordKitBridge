package br.asthen.kitbridge.core;

public record DeliveryOptions(
        boolean allowDiscordDelivery,
        boolean requireOnline,
        boolean pendingIfInventoryFull,
        boolean preventDuplicate,
        int requestTimeoutSeconds,
        String floodgatePrefix) {
}
