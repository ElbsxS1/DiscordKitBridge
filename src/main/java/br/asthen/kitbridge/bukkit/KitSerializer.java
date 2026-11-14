package br.asthen.kitbridge.bukkit;

import br.asthen.kitbridge.kit.KitData;
import br.asthen.kitbridge.kit.KitManager.KitException;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
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
        return new KitData(name, System.currentTimeMillis(), creator, true, slots);
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
}
