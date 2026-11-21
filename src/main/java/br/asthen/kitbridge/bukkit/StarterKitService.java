package br.asthen.kitbridge.bukkit;

import br.asthen.kitbridge.DiscordKitBridge;
import br.asthen.kitbridge.core.StarterKitPolicy;
import br.asthen.kitbridge.core.StarterKitReceived;
import br.asthen.kitbridge.core.StarterKitSettings;
import br.asthen.kitbridge.kit.KitData;
import br.asthen.kitbridge.kit.KitManager.KitException;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Delivers the Kit Inicial: the items that are INSIDE the registered Shulker Box, straight into the inventory
 * (no Shulker Box item is created).
 *
 * <p>This path is completely separate from {@code DeliveryService}: it creates no request record, no pending entry,
 * no outbox event and nothing is ever reported to the Discord bot. The only trace is a line in the local delivery log.
 * Must be called on the server thread.
 */
public final class StarterKitService {

    public enum Result { DELIVERED, COOLDOWN, NO_KIT, CORRUPT, DISABLED, ALREADY_RECEIVED }

    private final DiscordKitBridge plugin;
    private final StarterKitPolicy policy = new StarterKitPolicy();
    private final StarterKitReceived received;
    private volatile boolean warnedMissing;

    public StarterKitService(DiscordKitBridge plugin, StarterKitReceived received) {
        this.plugin = plugin;
        this.received = received;
    }

    public StarterKitPolicy policy() {
        return policy;
    }

    /**
     * Join / entering survival: delivers only to a player who never received the kit (when first-time-only is on).
     * The check and the "received" mark are both made on the server thread, so two triggers cannot both pass.
     */
    public Result giveFirstTime(Player p, String trigger) {
        if (plugin.starterSettings().firstTimeOnly() && received.has(p.getUniqueId())) return Result.ALREADY_RECEIVED;
        return give(p, trigger, true);
    }

    /** Death: always delivers (apart from the short cooldown against double triggers). */
    public Result giveOnDeath(Player p) {
        return give(p, "death", true);
    }

    /** @param respectCooldown false for the admin command, which always delivers */
    public Result give(Player p, String trigger, boolean respectCooldown) {
        StarterKitSettings s = plugin.starterSettings();
        if (!s.enabled()) return Result.DISABLED;

        Optional<KitData> found = plugin.kits().get(s.name());
        if (found.isEmpty()) {
            if (!warnedMissing) {
                warnedMissing = true;
                plugin.getLogger().warning("Kit Inicial \"" + s.name() + "\" não existe: ninguém recebe nada. "
                        + "Segure uma Shulker Box com os itens e use /dk create " + s.name());
            }
            return Result.NO_KIT;
        }
        warnedMissing = false;

        if (respectCooldown && !policy.tryAcquire(p.getUniqueId(), System.currentTimeMillis(), s.cooldownSeconds() * 1000L)) {
            return Result.COOLDOWN;
        }

        List<ItemStack> items;
        try {
            items = KitSerializer.toItems(found.get()); // everything is decoded BEFORE anything is given
        } catch (KitException e) {
            policy.release(p.getUniqueId());
            plugin.getLogger().severe("Kit Inicial corrompido, nada foi entregue a " + p.getName() + ": " + e.getMessage());
            plugin.logger().log("STARTER_KIT_FAILED", "player", p.getName(), "trigger", trigger, "error", e.getMessage());
            return Result.CORRUPT;
        }

        ItemStack[] give = new ItemStack[items.size()];
        for (int i = 0; i < give.length; i++) give[i] = items.get(i).clone();
        Map<Integer, ItemStack> left = p.getInventory().addItem(give);

        int dropped = 0;
        if (!left.isEmpty() && s.dropIfFull()) {
            for (ItemStack rest : left.values()) {
                Item drop = p.getWorld().dropItem(p.getLocation(), rest);
                drop.setOwner(p.getUniqueId()); // only this player can pick it up
                dropped += rest.getAmount();
            }
        }

        if (!trigger.equals("command")) received.add(p.getUniqueId()); // an admin test does not use up the first-time kit
        plugin.logger().log("STARTER_KIT", "player", p.getName(), "uuid", p.getUniqueId(), "trigger", trigger,
                "kit", found.get().name(), "items", items.size(), "dropped", dropped);
        if (!s.message().isEmpty()) {
            p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(s.message()));
        }
        return Result.DELIVERED;
    }
}
