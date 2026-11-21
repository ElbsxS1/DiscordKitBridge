package br.asthen.kitbridge.listener;

import br.asthen.kitbridge.DiscordKitBridge;
import br.asthen.kitbridge.core.StarterKitPolicy;
import br.asthen.kitbridge.core.StarterKitSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Triggers of the Kit Inicial: join, death (delivered after the respawn) and entering the survival world from the
 * lobby (what LobbyGuard's villager does with {@code p.teleport}, which fires {@link PlayerChangedWorldEvent}, so
 * LobbyGuard needs no change). Nothing here talks to the Discord bot.
 */
public final class StarterKitListener implements Listener {

    private final DiscordKitBridge plugin;
    /** Players who died and have not respawned yet. Death alone is not a trigger: some respawns are not deaths. */
    private final Set<UUID> died = ConcurrentHashMap.newKeySet();

    public StarterKitListener(DiscordKitBridge plugin) {
        this.plugin = plugin;
    }

    public void shutdown() {
        died.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        StarterKitSettings s = plugin.starterSettings();
        if (!s.enabled() || !s.onJoin()) return;
        Player p = e.getPlayer();
        // wait a moment: LobbyGuard moves the player to the lobby right after joining, and the world is checked after that
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            StarterKitSettings now = plugin.starterSettings();
            if (StarterKitPolicy.inWorlds(p.getWorld().getName(), now.joinSkipWorlds())) return;
            plugin.starterKit().give(p, "join");
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        StarterKitSettings s = plugin.starterSettings();
        if (s.enabled() && s.onDeath()) died.add(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!died.remove(p.getUniqueId())) return;
        // two ticks later: the respawn location (set by LobbyGuard or a bed) is final and the player is back in the world
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !p.isDead()) plugin.starterKit().give(p, "death");
        }, 2L);
    }

    /** Lobby -> survival (the villager NPC). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        StarterKitSettings s = plugin.starterSettings();
        if (!s.enabled() || !s.matchesEnter(e.getFrom().getName(), p.getWorld().getName())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.starterKit().give(p, "enter-world");
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        died.remove(e.getPlayer().getUniqueId());
        plugin.starterKit().policy().forget(e.getPlayer().getUniqueId());
    }
}
