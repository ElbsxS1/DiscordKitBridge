package br.asthen.kitbridge.listener;

import br.asthen.kitbridge.DiscordKitBridge;
import br.asthen.kitbridge.bukkit.PreviewHolder;
import br.asthen.kitbridge.core.DeliveryService;
import br.asthen.kitbridge.core.Platform;
import br.asthen.kitbridge.core.PlayerRef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joins, previews and AUTO-CLAIM.
 *
 * <p>Auto-claim: when something may have freed inventory space (closing an inventory, dropping an item, clicking in
 * the inventory) and the player has a pending kit, one new attempt is scheduled for the NEXT tick. Rules that keep
 * it cheap and safe:
 * <ul>
 *   <li>the check is memory-only ({@code hasClaimableByUuid}); players without pending kits cost one map lookup;</li>
 *   <li>debounce: at most one attempt scheduled per player; after an attempt a short cooldown applies;</li>
 *   <li>no loops: an attempt never schedules another one by itself, only a new player action does;</li>
 *   <li>the "inventory full" notice is sent at most every {@value #NOTICE_EVERY_MS} ms per player;</li>
 *   <li>the delivery itself is {@link DeliveryService#claimDetailed}: persisted CLAIMING state, one claim per player.</li>
 * </ul>
 */
public final class PlayerListener implements Listener {

    static final long COOLDOWN_MS = 750L;
    static final long NOTICE_EVERY_MS = 30_000L;

    private final DiscordKitBridge plugin;
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastAttempt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public PlayerListener(DiscordKitBridge plugin) {
        this.plugin = plugin;
    }

    public void shutdown() {
        scheduled.clear();
        lastAttempt.clear();
        lastNotice.clear();
    }

    /** Delivers kits that were waiting for this player (offline when requested, or inventory was full). */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // wait a moment: Floodgate and the client need to finish joining first
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            PlayerRef ref = new PlayerRef(p.getUniqueId(), p.getName());
            Platform platform = plugin.game().platformOf(p);
            if (plugin.service().pendingCount(ref, platform) > 0) {
                DeliveryService.ClaimResult r = plugin.service().claimDetailed(ref, platform, true);
                if (r.inventoryFull()) lastNotice.put(p.getUniqueId(), System.currentTimeMillis());
            }
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        scheduled.remove(id);
        lastAttempt.remove(id);
        lastNotice.remove(id);
    }

    // ------------------------------------------------------------ auto-claim triggers (space may have been freed)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) requestClaim(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        requestClaim(e.getPlayer());
    }

    /** /dk preview is look-only: block every way of taking or moving items. Also an auto-claim trigger. */
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PreviewHolder) {
            e.setCancelled(true);
            return;
        }
        if (e.getWhoClicked() instanceof Player p) requestClaim(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PreviewHolder) e.setCancelled(true);
    }

    /** Schedules at most one attempt, on the next tick, for a player who actually has something pending. */
    void requestClaim(Player p) {
        UUID id = p.getUniqueId();
        if (!plugin.service().hasClaimableByUuid(id)) return;        // memory only
        if (!scheduled.add(id)) return;                              // debounce: already scheduled
        long now = System.currentTimeMillis();
        Long last = lastAttempt.get(id);
        long delayTicks = (last != null && now - last < COOLDOWN_MS) ? 15L : 1L; // cooldown after a recent attempt
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduled.remove(id);
            if (!p.isOnline()) return;
            lastAttempt.put(id, System.currentTimeMillis());
            PlayerRef ref = new PlayerRef(id, p.getName());
            DeliveryService.ClaimResult r = plugin.service().claimDetailed(ref, plugin.game().platformOf(p), false);
            if (r.inventoryFull()) {
                long t = System.currentTimeMillis();
                Long n = lastNotice.get(id);
                if (n == null || t - n >= NOTICE_EVERY_MS) {
                    lastNotice.put(id, t);
                    plugin.game().tell(ref, DeliveryService.PENDING_NOTICE);
                }
            }
        }, delayTicks);
    }
}
