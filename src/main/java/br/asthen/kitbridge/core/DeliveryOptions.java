package br.asthen.kitbridge.core;

/**
 * @param starterKitName name of the Kit Inicial (see {@link StarterKitPolicy}). That kit is NEVER offered to the
 *                       Discord bot: it is hidden from the kit list and refused as a delivery request.
 */
public record DeliveryOptions(
        boolean allowDiscordDelivery,
        boolean requireOnline,
        boolean pendingIfInventoryFull,
        boolean preventDuplicate,
        int requestTimeoutSeconds,
        String floodgatePrefix,
        String starterKitName) {

    /** Same options without a starter kit name (the default name {@link StarterKitPolicy#DEFAULT_NAME} is used). */
    public DeliveryOptions(boolean allowDiscordDelivery, boolean requireOnline, boolean pendingIfInventoryFull,
                           boolean preventDuplicate, int requestTimeoutSeconds, String floodgatePrefix) {
        this(allowDiscordDelivery, requireOnline, pendingIfInventoryFull, preventDuplicate, requestTimeoutSeconds,
                floodgatePrefix, StarterKitPolicy.DEFAULT_NAME);
    }
}
