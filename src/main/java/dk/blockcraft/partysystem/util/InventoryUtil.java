package dk.blockcraft.partysystem.util;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class InventoryUtil {
	public static final int SIZE = 41;
	private static final int STORAGE = 36;
	private static final int OFFHAND = 40;

	private InventoryUtil() {
	}

	public static ItemStack[] snapshot(PlayerInventory inv) {
		ItemStack[] out = new ItemStack[41];
		ItemStack[] storage = inv.getStorageContents();
		for (int i = 0; i < 36 && i < storage.length; ++i) {
			out[i] = InventoryUtil.copy(storage[i]);
		}
		ItemStack[] armor = inv.getArmorContents();
		for (int i = 0; i < 4 && i < armor.length; ++i) {
			out[36 + i] = InventoryUtil.copy(armor[i]);
		}
		out[40] = InventoryUtil.copy(inv.getItemInOffHand());
		return out;
	}

	public static void apply(PlayerInventory inv, ItemStack[] kit) {
		inv.clear();
		ItemStack[] storage = new ItemStack[36];
		for (int i = 0; i < 36; ++i) {
			storage[i] = InventoryUtil.get(kit, i);
		}
		ItemStack[] armor = new ItemStack[4];
		for (int i = 0; i < 4; ++i) {
			armor[i] = InventoryUtil.get(kit, 36 + i);
		}
		inv.setStorageContents(storage);
		inv.setArmorContents(armor);
		ItemStack off = InventoryUtil.get(kit, 40);
		inv.setItemInOffHand(off == null ? new ItemStack(Material.AIR) : off);
	}

	public static List<ItemStack> fill(PlayerInventory inv, ItemStack[] kit) {
		ArrayList<ItemStack> left = new ArrayList<ItemStack>();
		for (int i = 0; i < 36; ++i) {
			ItemStack item = InventoryUtil.get(kit, i);
			if (item == null) continue;
			ItemStack current = inv.getItem(i);
			if (current == null || current.getType().isAir()) {
				inv.setItem(i, item);
				continue;
			}
			InventoryUtil.add(inv, item, left);
		}
		ItemStack[] armor = inv.getArmorContents();
		for (int i = 0; i < 4; ++i) {
			ItemStack item = InventoryUtil.get(kit, 36 + i);
			if (item == null) continue;
			if (armor[i] == null || armor[i].getType().isAir()) {
				armor[i] = item;
				continue;
			}
			InventoryUtil.add(inv, item, left);
		}
		inv.setArmorContents(armor);
		ItemStack off = InventoryUtil.get(kit, 40);
		if (off != null) {
			if (inv.getItemInOffHand().getType().isAir()) {
				inv.setItemInOffHand(off);
			} else {
				InventoryUtil.add(inv, off, left);
			}
		}
		return left;
	}

	private static void add(PlayerInventory inv, ItemStack item, List<ItemStack> left) {
		left.addAll(inv.addItem(item).values());
	}

	private static ItemStack get(ItemStack[] kit, int index) {
		return index < kit.length ? InventoryUtil.copy(kit[index]) : null;
	}

	private static ItemStack copy(ItemStack item) {
		return item == null || item.getType().isAir() ? null : item.clone();
	}
}