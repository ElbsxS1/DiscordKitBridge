package br.asthen.kitbridge.bukkit;

import br.asthen.kitbridge.kit.KitData;
import br.asthen.kitbridge.kit.KitManager.KitException;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts between a Shulker Box item and {@link KitData}.
 * Items are stored with {@code ItemStack#serializeAsBytes()}: the native, versioned format the
 * server itself uses, so nothing on the item is lost or reinterpreted.
 */
public final class KitSerializer {

    private KitSerializer() {
    }

    public static KitData fromShulker(ItemStack held, String name, String creator) throws KitException {
        if (held == null || held.isEmpty() || !Tag.ITEMS_SHULKER_BOXES.isTagged(held.getType())) {
            throw new KitException("Segure uma Shulker Box na mão principal.");
        }
        if (!(held.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            throw new KitException("Essa Shulker Box está vazia.");
        }
        ItemStack[] contents = box.getSnapshotInventory().getContents();
        List<KitData.Slot> slots = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.isEmpty()) continue;
            slots.add(new KitData.Slot(i, it.serializeAsBytes()));
        }
        if (slots.isEmpty()) throw new KitException("Essa Shulker Box está vazia.");
        return new KitData(name, System.currentTimeMillis(), creator, true, slots, KitData.NO_PRICE, held.getType().name());
    }

    /** @throws KitException if any stored item can no longer be read (nothing is partially returned) */
    public static List<ItemStack> toItems(KitData kit) throws KitException {
        List<ItemStack> items = new ArrayList<>(kit.slots().size());
        for (KitData.Slot s : kit.slots()) {
            try {
                ItemStack it = ItemStack.deserializeBytes(s.data());
                if (it.isEmpty()) throw new KitException("Item vazio no slot " + s.slot());
                items.add(it);
            } catch (KitException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new KitException("Item ilegível no slot " + s.slot() + ": " + e.getMessage());
            }
        }
        return items;
    }

    /**
     * Builds the item a player receives: a Shulker Box (the colour saved with the kit, purple for kits
     * registered before colours were stored) with the kit's items inside, each in its original slot.
     *
     * @param items the decoded items, in the same order as {@code kit.slots()} (see {@link #toItems})
     */
    public static ItemStack toShulker(KitData kit, List<ItemStack> items) throws KitException {
        Material type = kit.box() == null ? null : Material.matchMaterial(kit.box());
        if (type == null || !Tag.ITEMS_SHULKER_BOXES.isTagged(type)) type = Material.SHULKER_BOX;

        ItemStack box = new ItemStack(type);
        if (!(box.getItemMeta() instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof ShulkerBox state)) {
            throw new KitException("Não foi possível montar a Shulker Box do kit.");
        }
        Inventory inv = state.getSnapshotInventory();
        List<KitData.Slot> slots = kit.slots();
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i).slot();
            if (slot < 0 || slot >= 27) throw new KitException("Slot inválido no kit: " + slot);
            inv.setItem(slot, items.get(i));
        }
        meta.setBlockState(state);
        meta.itemName(Component.text(kit.name()));
        box.setItemMeta(meta);
        return box;
    }
}
