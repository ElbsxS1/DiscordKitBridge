package br.asthen.kitbridge.listener;

import br.asthen.kitbridge.DiscordKitBridge;
import br.asthen.kitbridge.bukkit.PreviewHolder;
import br.asthen.kitbridge.core.Platform;
import br.asthen.kitbridge.core.PlayerRef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerListener implements Listener {

    private final DiscordKitBridge plugin;

    public PlayerListener(DiscordKitBridge plugin) {
        this.plugin = plugin;
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
            if (plugin.service().pendingCount(ref, platform) > 0) plugin.service().claim(ref, platform);
        }, 60L);
    }

    /** /dk preview is look-only: block every way of taking or moving items. */
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PreviewHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PreviewHolder) e.setCancelled(true);
    }
}
