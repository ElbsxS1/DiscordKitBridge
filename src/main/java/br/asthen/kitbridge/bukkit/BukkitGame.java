package br.asthen.kitbridge.bukkit;

import br.asthen.kitbridge.core.DeliveryOptions;
import br.asthen.kitbridge.core.GameAdapter;
import br.asthen.kitbridge.core.Names;
import br.asthen.kitbridge.core.Platform;
import br.asthen.kitbridge.core.PlayerRef;
import br.asthen.kitbridge.kit.KitData;
import br.asthen.kitbridge.kit.KitManager.KitException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Paper implementation of {@link GameAdapter}. Every method must run on the server thread. */
public final class BukkitGame implements GameAdapter {

    private final PlatformManager platforms;
    private final Supplier<DeliveryOptions> options;
    private final BooleanSupplier allowUnverified;

    public BukkitGame(PlatformManager platforms, Supplier<DeliveryOptions> options, BooleanSupplier allowUnverified) {
        this.platforms = platforms;
        this.options = options;
        this.allowUnverified = allowUnverified;
    }

    @Override
    public Lookup find(String name, Platform platform) {
        String prefix = options.get().floodgatePrefix();
        String want = Names.normalize(name, prefix);
        boolean floodgate = platforms.available();

        Player bedrock = null;
        Player java = null;
        Player unverified = null;
        boolean anyCandidate = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Names.normalize(p.getName(), prefix).equals(want)) continue;
            anyCandidate = true;
            Boolean br = platforms.isBedrock(p.getUniqueId());
            if (Boolean.TRUE.equals(br)) bedrock = p;
            else if (Boolean.FALSE.equals(br)) java = p;
            else unverified = p; // Floodgate not available
        }
        if (!anyCandidate) return Lookup.of(Outcome.OFFLINE);

        if (platform == Platform.BEDROCK) {
            if (bedrock != null) return online(bedrock, Platform.BEDROCK);
            if (!floodgate) {
                // Without Floodgate the platform cannot be proven. Only accept the Floodgate-style
                // prefix when the admin explicitly allows it.
                boolean prefixed = !prefix.isEmpty() && unverified != null
                        && unverified.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
                if (allowUnverified.getAsBoolean() && prefixed) return online(unverified, Platform.BEDROCK);
                return Lookup.of(Outcome.UNVERIFIABLE);
            }
            return Lookup.of(Outcome.MISMATCH);
        }

        // JAVA
        if (java != null) return online(java, Platform.JAVA);
        if (unverified != null) return online(unverified, Platform.JAVA);
        return Lookup.of(Outcome.MISMATCH);
    }

    private static Lookup online(Player p, Platform actual) {
        return new Lookup(Outcome.ONLINE, new PlayerRef(p.getUniqueId(), p.getName()), actual);
    }

    /** The platform of a connected player, or null when it cannot be proven. */
    public Platform platformOf(Player p) {
        Boolean br = platforms.isBedrock(p.getUniqueId());
        if (br != null) return br ? Platform.BEDROCK : Platform.JAVA;
        String prefix = options.get().floodgatePrefix();
        boolean prefixed = !prefix.isEmpty() && p.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
        if (prefixed) return allowUnverified.getAsBoolean() ? Platform.BEDROCK : null;
        return Platform.JAVA;
    }

    @Override
    public GiveResult give(PlayerRef ref, KitData kit) {
        Player p = Bukkit.getPlayer(ref.uuid());
        if (p == null) return GiveResult.OFFLINE;

        List<ItemStack> items;
        try {
            // everything is decoded and the box is built BEFORE anything is given. The player receives ONE
            // item: a Shulker Box with the kit inside, so the kit needs a single free slot.
            items = List.of(KitSerializer.toShulker(kit, KitSerializer.toItems(kit)));
        } catch (KitException e) {
            return GiveResult.CORRUPT;
        }

        PlayerInventory inv = p.getInventory();
        ItemStack[] before = clone(inv.getStorageContents());

        // 1) dry run on a scratch inventory: does the box fit?
        Inventory scratch = Bukkit.createInventory(null, 36);
        scratch.setContents(clone(before));
        if (!scratch.addItem(cloneAll(items)).isEmpty()) return GiveResult.FULL;

        // 2) real delivery; if anything still did not fit, put the inventory back exactly as it was
        if (!inv.addItem(cloneAll(items)).isEmpty()) {
            inv.setStorageContents(before);
            return GiveResult.FULL;
        }
        return GiveResult.DELIVERED;
    }

    @Override
    public void tell(PlayerRef ref, String message) {
        Player p = Bukkit.getPlayer(ref.uuid());
        if (p != null) p.sendMessage(Component.text(Msg.PREFIX + message, NamedTextColor.GREEN));
    }

    private static ItemStack[] clone(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
        return out;
    }

    private static ItemStack[] cloneAll(List<ItemStack> items) {
        List<ItemStack> l = new ArrayList<>(items.size());
        for (ItemStack i : items) l.add(i.clone());
        return l.toArray(new ItemStack[0]);
    }
}
