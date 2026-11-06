package dk.blockcraft.partysystem.util;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

public final class KitSanitizer {
	private KitSanitizer() {
	}

	public static Result sanitize(ItemStack[] input, ConfigurationSection cfg) {
		ItemStack[] out = new ItemStack[41];
		int changed = 0;
		boolean whitelist = "WHITELIST".equalsIgnoreCase(cfg.getString("kit.filter.mode", "BLACKLIST"));
		HashSet<Material> filter = new HashSet<Material>();
		for (String name : cfg.getStringList("kit.filter.materials")) {
			Material m = Material.matchMaterial(name);
			if (m == null) continue;
			filter.add(m);
		}
		boolean removeContainers = cfg.getBoolean("kit.filter.remove-filled-containers", true);
		int maxTotal = cfg.getInt("kit.limits.max-total-items", 0);
		EnumMap<Material, Integer> perMaterial = new EnumMap<Material, Integer>(Material.class);
		ConfigurationSection per = cfg.getConfigurationSection("kit.limits.per-material");
		if (per != null) {
			for (String key : per.getKeys(false)) {
				Material m = Material.matchMaterial(key);
				if (m == null) continue;
				perMaterial.put(m, Math.max(0, per.getInt(key)));
			}
		}
		boolean allowUnsafe = cfg.getBoolean("kit.enchantments.allow-unsafe", false);
		boolean removeInapplicable = cfg.getBoolean("kit.enchantments.remove-inapplicable", true);
		HashSet<String> blocked = new HashSet<String>();
		for (String s : cfg.getStringList("kit.enchantments.blocked")) {
			blocked.add(KitSanitizer.plainKey(s));
		}
		ConfigurationSection maxLevels = cfg.getConfigurationSection("kit.enchantments.max-levels");
		HashMap<String, Integer> levelCaps = new HashMap<String, Integer>();
		if (maxLevels != null) {
			for (String key : maxLevels.getKeys(false)) {
				levelCaps.put(KitSanitizer.plainKey(key), Math.max(1, maxLevels.getInt(key)));
			}
		}
		boolean stripName = cfg.getBoolean("kit.strip.custom-name", false);
		boolean stripLore = cfg.getBoolean("kit.strip.lore", false);
		boolean stripData = cfg.getBoolean("kit.strip.custom-data", true);
		EnumMap<Material, Integer> counts = new EnumMap<Material, Integer>(Material.class);
		int total = 0;
		boolean any = false;
		for (int i = 0; i < 41; ++i) {
			Integer limit;
			int amount;
			ItemStack original;
			ItemStack itemStack = original = i < input.length ? input[i] : null;
			if (original == null || original.getType().isAir()) continue;
			ItemStack item = original.clone();
			Material type = item.getType();
			boolean listed = filter.contains(type);
			if (whitelist ? !listed : listed) {
				++changed;
				continue;
			}
			if (removeContainers && KitSanitizer.isFilledContainer(item)) {
				++changed;
				continue;
			}
			if (KitSanitizer.fixEnchantments(item, allowUnsafe, removeInapplicable, blocked, levelCaps)) {
				++changed;
			}
			KitSanitizer.strip(item, stripName, stripLore, stripData);
			int allowed = amount = item.getAmount();
			if (maxTotal > 0) {
				allowed = Math.min(allowed, maxTotal - total);
			}
			if ((limit = (Integer)perMaterial.get(type)) != null) {
				allowed = Math.min(allowed, limit - counts.getOrDefault(type, 0));
			}
			if (allowed <= 0) {
				++changed;
				continue;
			}
			if (allowed < amount) {
				item.setAmount(allowed);
				++changed;
			}
			total += allowed;
			counts.merge(type, allowed, Integer::sum);
			out[i] = item;
			any = true;
		}
		return new Result(out, changed, !any);
	}

	private static String plainKey(String key) {
		String k = key.toLowerCase(Locale.ROOT).trim();
		int colon = k.indexOf(58);
		return colon >= 0 ? k.substring(colon + 1) : k;
	}

	private static boolean isFilledContainer(ItemStack item) {
		try {
			BundleMeta bundle;
			Container container;
			BlockStateMeta bsm;
			BlockState state;
			ItemMeta meta = item.getItemMeta();
			if (meta instanceof BlockStateMeta && (state = (bsm = (BlockStateMeta)meta).getBlockState()) instanceof Container && !(container = (Container)state).getSnapshotInventory().isEmpty()) {
				return true;
			}
			return meta instanceof BundleMeta && (bundle = (BundleMeta)meta).hasItems();
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	private static boolean fixEnchantments(ItemStack item, boolean allowUnsafe, boolean removeInapplicable, Set<String> blocked, Map<String, Integer> levelCaps) {
		boolean book = item.getType() == Material.ENCHANTED_BOOK;
		HashMap<Enchantment, Integer> current = new HashMap<Enchantment, Integer>();
		ItemMeta meta = item.getItemMeta();
		if (book) {
			if (!(meta instanceof EnchantmentStorageMeta)) {
				return false;
			}
			EnchantmentStorageMeta storage = (EnchantmentStorageMeta)meta;
			current.putAll(storage.getStoredEnchants());
		} else {
			current.putAll(item.getEnchantments());
		}
		if (current.isEmpty()) {
			return false;
		}
		HashMap<Enchantment, Integer> fixed = new HashMap<Enchantment, Integer>();
		for (Map.Entry entry : current.entrySet()) {
			Enchantment enchantment = (Enchantment)entry.getKey();
			String key = enchantment.getKey().getKey();
			if (blocked.contains(key) || !book && removeInapplicable && !enchantment.canEnchantItem(item)) continue;
			int max = levelCaps.containsKey(key) ? levelCaps.get(key) : (allowUnsafe ? Integer.MAX_VALUE : enchantment.getMaxLevel());
			fixed.put(enchantment, Math.min((Integer)entry.getValue(), max));
		}
		if (fixed.equals(current)) {
			return false;
		}
		if (book) {
			EnchantmentStorageMeta storage = (EnchantmentStorageMeta)meta;
			for (Enchantment enchantment : new ArrayList<Enchantment>(storage.getStoredEnchants().keySet())) {
				storage.removeStoredEnchant(enchantment);
			}
			for (Map.Entry entry : fixed.entrySet()) {
				storage.addStoredEnchant((Enchantment)entry.getKey(), (Integer)entry.getValue(), true);
			}
			item.setItemMeta(storage);
		} else {
			for (Enchantment enchantment : new ArrayList<Enchantment>(item.getEnchantments().keySet())) {
				item.removeEnchantment(enchantment);
			}
			for (Map.Entry entry : fixed.entrySet()) {
				item.addUnsafeEnchantment((Enchantment)entry.getKey(), (Integer)entry.getValue());
			}
		}
		return true;
	}

	private static void strip(ItemStack item, boolean name, boolean lore, boolean data) {
		if (!(name || lore || data)) {
			return;
		}
		ItemMeta meta = item.getItemMeta();
		if (meta == null) {
			return;
		}
		if (name) {
			meta.displayName(null);
		}
		if (lore) {
			meta.lore(null);
		}
		if (data) {
			PersistentDataContainer pdc = meta.getPersistentDataContainer();
			ArrayList<NamespacedKey> keys = new ArrayList<NamespacedKey>(pdc.getKeys());
			for (NamespacedKey key : keys) {
				pdc.remove(key);
			}
		}
		item.setItemMeta(meta);
	}

	public record Result(ItemStack[] items, int changed, boolean empty) {
	}
}