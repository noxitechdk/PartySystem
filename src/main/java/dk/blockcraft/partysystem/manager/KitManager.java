package dk.blockcraft.partysystem.manager;

import dk.blockcraft.partysystem.PartySystem;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

public final class KitManager {
	private static final List<String> DEFAULTS = List.of("spear-mace", "mace", "sword", "uhc", "axe", "crystal", "netherite-pot", "pot", "smp", "diamond-smp", "spear-elytra", "shieldless-uhc", "lt-cart", "creeper", "bow", "diamond-crystal", "trident");
	private static final Map<String, String> DEFAULT_ICONS = Map.ofEntries(Map.entry("trident", "TRIDENT"), Map.entry("spear-elytra", "ELYTRA"), Map.entry("spear-mace", "NETHERITE_SPEAR"), Map.entry("mace", "MACE"), Map.entry("axe", "NETHERITE_AXE"), Map.entry("crystal", "END_CRYSTAL"), Map.entry("diamond-crystal", "END_CRYSTAL"), Map.entry("pot", "SPLASH_POTION"), Map.entry("netherite-pot", "SPLASH_POTION"), Map.entry("bow", "BOW"), Map.entry("lt-cart", "TNT_MINECART"), Map.entry("creeper", "CREEPER_HEAD"));
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private final PartySystem plugin;
	private volatile Map<String, Kit> kits = Map.of();

	public KitManager(PartySystem plugin) {
		this.plugin = plugin;
	}

	public void load() {
		File dir = new File(this.plugin.getDataFolder(), "Kits");
		if (!dir.exists()) {
			dir.mkdirs();
			for (String name2 : DEFAULTS) {
				this.plugin.saveResource("Kits/" + name2 + ".yml", false);
			}
		}
		TreeMap<String, Kit> loaded = new TreeMap<String, Kit>();
		File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
		if (files != null) {
			for (File file : files) {
				String fileName = file.getName();
				String id = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.ROOT);
				try {
					Kit kit = this.parse(id, YamlConfiguration.loadConfiguration(file));
					if (kit == null) continue;
					loaded.put(id, kit);
				}
				catch (RuntimeException ex) {
					this.plugin.getLogger().log(Level.WARNING, "Kit '" + fileName + "' kunne ikke indl\u00e6ses: " + ex.getMessage(), ex);
				}
			}
		}
		this.kits = loaded;
		this.plugin.getLogger().info(loaded.size() + " kit(s) indl\u00e6st fra Kits-mappen.");
	}

	public Kit get(String id) {
		return id == null ? null : this.kits.get(id.toLowerCase(Locale.ROOT));
	}

	public List<Kit> all() {
		return new ArrayList<Kit>(this.kits.values());
	}

	public List<String> ids() {
		return new ArrayList<String>(this.kits.keySet());
	}

	public static boolean validId(String id) {
		return id != null && id.matches("[a-z0-9_-]{1,32}");
	}

	public void saveKit(String id, ItemStack[] items) throws IOException {
		YamlConfiguration yaml;
		File dir = new File(this.plugin.getDataFolder(), "Kits");
		dir.mkdirs();
		File file = new File(dir, id + ".yml");
		YamlConfiguration yamlConfiguration = yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
		if (!yaml.contains("display-name")) {
			yaml.set("display-name", id);
		}
		ArrayList<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		for (int slot = 0; slot < 41 && slot < items.length; ++slot) {
			ItemStack item = items[slot];
			if (item == null || item.getType().isAir()) continue;
			list.add(this.serialize(slot, item));
		}
		yaml.set("items", list);
		yaml.save(file);
	}

	private Map<String, Object> serialize(int slot, ItemStack item) {
		List<Map<String, Object>> contents;
		PotionMeta potionMeta;
		List<Component> lore;
		ItemMeta meta;
		LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
		out.put("slot", slot >= 36 ? this.slotName(slot) : Integer.valueOf(slot));
		out.put("material", item.getType().name());
		if (item.getAmount() > 1) {
			out.put("amount", item.getAmount());
		}
		if ((meta = item.getItemMeta()) == null) {
			return out;
		}
		if (meta.displayName() != null) {
			out.put("name", this.plainMini((String)MM.serialize(meta.displayName())));
		}
		if ((lore = meta.lore()) != null && !lore.isEmpty()) {
			ArrayList<String> lines = new ArrayList<String>();
			for (Component c : lore) {
				lines.add(this.plainMini((String)MM.serialize(c)));
			}
			out.put("lore", lines);
		}
		if (meta instanceof PotionMeta && (potionMeta = (PotionMeta)meta).getBasePotionType() != null) {
			out.put("potion", potionMeta.getBasePotionType().getKey().getKey());
		}
		if (meta.isUnbreakable()) {
			out.put("unbreakable", true);
		}
		if (!(contents = this.serializeContents(meta)).isEmpty()) {
			out.put("contents", contents);
		}
		Map<Enchantment, Integer> enchants = meta instanceof EnchantmentStorageMeta ? ((EnchantmentStorageMeta)meta).getStoredEnchants() : item.getEnchantments();
		if (!enchants.isEmpty()) {
			TreeMap<String, Integer> sorted = new TreeMap<String, Integer>();
			for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
				sorted.put(e.getKey().getKey().getKey(), e.getValue());
			}
			out.put("enchantments", sorted);
		}
		return out;
	}

	private List<Map<String, Object>> serializeContents(ItemMeta meta) {
		ArrayList<Map<String, Object>> list;
		block4: {
			block3: {
				BlockStateMeta bsm;
				list = new ArrayList<Map<String, Object>>();
				if (!(meta instanceof BlockStateMeta) || !(bsm = (BlockStateMeta)meta).hasBlockState()) break block3;
				BlockState state = bsm.getBlockState();
				if (!(state instanceof Container)) break block4;
				Container container = (Container)state;
				Inventory inv = container.getSnapshotInventory();
				for (int slot = 0; slot < inv.getSize(); ++slot) {
					ItemStack inner = inv.getItem(slot);
					if (inner == null || inner.getType().isAir()) continue;
					list.add(this.serialize(slot, inner));
				}
				break block4;
			}
			if (meta instanceof BundleMeta) {
				BundleMeta bundle = (BundleMeta)meta;
				int i = 0;
				for (ItemStack inner : bundle.getItems()) {
					list.add(this.serialize(i++, inner));
				}
			}
		}
		return list;
	}

	private String slotName(int slot) {
		return switch (slot) {
			case 36 -> "boots";
			case 37 -> "leggings";
			case 38 -> "chestplate";
			case 39 -> "helmet";
			default -> "offhand";
		};
	}

	private String plainMini(String text) {
		return text.startsWith("<!italic>") ? text.substring("<!italic>".length()) : text;
	}

	private Kit parse(String id, YamlConfiguration yaml) {
		String display = yaml.getString("display-name", id);
		Material icon = null;
		String iconName = yaml.getString("icon", DEFAULT_ICONS.get(id));
		if (iconName != null && (icon = this.material(iconName)) == null) {
			this.warn(id, "ukendt icon: " + iconName);
		}
		ItemStack[] items = new ItemStack[41];
		int count = 0;
		for (Map<?, ?> raw : yaml.getMapList("items")) {
			int slot = this.slotOf(raw.get("slot"));
			if (slot < 0) {
				this.warn(id, "ugyldig slot: " + String.valueOf(raw.get("slot")));
				continue;
			}
			ItemStack item = this.buildItem(id, raw);
			if (item == null) continue;
			items[slot] = item;
			++count;
		}
		if (count == 0) {
			this.warn(id, "kittet har ingen gyldige items og springes over.");
			return null;
		}
		return new Kit(id, display, items, icon);
	}

	private int slotOf(Object value) {
		String s;
		if (value instanceof Number) {
			Number number = (Number)value;
			int slot = number.intValue();
			return slot >= 0 && slot <= 35 ? slot : -1;
		}
		if (value == null) {
			return -1;
		}
		switch (s = value.toString().trim().toLowerCase(Locale.ROOT)) {
			case "boots": {
				return 36;
			}
			case "leggings": {
				return 37;
			}
			case "chestplate": {
				return 38;
			}
			case "helmet": {
				return 39;
			}
			case "offhand": {
				return 40;
			}
		}
		try {
			int slot = Integer.parseInt(s);
			return slot >= 0 && slot <= 35 ? slot : -1;
		}
		catch (NumberFormatException ex) {
			return -1;
		}
	}

	private ItemStack buildItem(String id, Map<?, ?> raw) {
		Object object;
		ItemStack item;
		ItemMeta meta;
		Material material = this.material(raw.get("material"));
		if (material == null && (material = this.material(raw.get("fallback"))) != null) {
			this.warn(id, "materialet " + String.valueOf(raw.get("material")) + " findes ikke p\u00e5 denne server, bruger " + String.valueOf(material) + ".");
		}
		if (material == null) {
			this.warn(id, "ukendt material: " + String.valueOf(raw.get("material")));
			return null;
		}
		int amount = 1;
		Object obj = raw.get("amount");
		if (obj instanceof Number) {
			Number number = (Number)obj;
			amount = Math.max(1, Math.min(99, number.intValue()));
		}
		if ((meta = (item = new ItemStack(material, amount)).getItemMeta()) == null) {
			return item;
		}
		if (raw.get("name") != null) {
			meta.displayName(this.line(raw.get("name").toString()));
		}
		if ((object = raw.get("lore")) instanceof List) {
			List lore = (List)object;
			ArrayList<Component> lines = new ArrayList<Component>();
			for (Object entry : lore) {
				lines.add(this.line(String.valueOf(entry)));
			}
			meta.lore(lines);
		}
		if (Boolean.TRUE.equals(raw.get("unbreakable"))) {
			meta.setUnbreakable(true);
		}
		if (raw.get("potion") != null && meta instanceof PotionMeta) {
			PotionMeta potionMeta = (PotionMeta)meta;
			PotionType type = RegistryAccess.registryAccess().getRegistry(RegistryKey.POTION).get(NamespacedKey.minecraft(this.plain(raw.get("potion").toString())));
			if (type == null) {
				this.warn(id, "ukendt potion: " + String.valueOf(raw.get("potion")));
			} else {
				potionMeta.setBasePotionType(type);
			}
		}
		if ((object = raw.get("enchantments")) instanceof Map) {
			Map<?, ?> enchants = (Map<?, ?>)object;
			for (Map.Entry<?, ?> entry : enchants.entrySet()) {
				int level;
				Enchantment ench = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(this.plain(String.valueOf(entry.getKey()))));
				if (ench == null) {
					this.warn(id, "ukendt enchantment: " + String.valueOf(entry.getKey()));
					continue;
				}
				Object v = entry.getValue();
				if (v instanceof Number) {
					Number n = (Number)v;
					level = Math.max(1, n.intValue());
				} else {
					level = 1;
				}
				if (meta instanceof EnchantmentStorageMeta) {
					EnchantmentStorageMeta storage = (EnchantmentStorageMeta)meta;
					storage.addStoredEnchant(ench, level, true);
					continue;
				}
				meta.addEnchant(ench, level, true);
			}
		}
		if ((object = raw.get("contents")) instanceof List) {
			List contents = (List)object;
			this.applyContents(id, meta, contents);
		}
		item.setItemMeta(meta);
		return item;
	}

	private void applyContents(String id, ItemMeta meta, List<?> contents) {
		BlockStateMeta bsm;
		BlockState blockState;
		if (meta instanceof BlockStateMeta && (blockState = (bsm = (BlockStateMeta)meta).getBlockState()) instanceof Container) {
			Container container = (Container)blockState;
			Inventory inv = container.getSnapshotInventory();
			for (Object entry : contents) {
				Map innerRaw;
				Object v;
				if (!(entry instanceof Map) || !((v = (innerRaw = (Map)entry).get("slot")) instanceof Number)) {
					this.warn(id, "ugyldigt item i contents: " + String.valueOf(entry));
					continue;
				}
				Number n = (Number)v;
				int slot = n.intValue();
				ItemStack inner = this.buildItem(id, innerRaw);
				if (inner == null) continue;
				if (slot < 0 || slot >= inv.getSize()) {
					this.warn(id, "ugyldig contents-slot: " + slot);
					continue;
				}
				inv.setItem(slot, inner);
			}
			bsm.setBlockState(container);
		} else if (meta instanceof BundleMeta) {
			BundleMeta bundle = (BundleMeta)meta;
			ArrayList<ItemStack> inner = new ArrayList<ItemStack>();
			for (Object entry : contents) {
				Map innerRaw;
				ItemStack built;
				if (!(entry instanceof Map) || (built = this.buildItem(id, innerRaw = (Map)entry)) == null) continue;
				inner.add(built);
			}
			bundle.setItems(inner);
		} else {
			this.warn(id, "'contents' bruges p\u00e5 et item der ikke er en container.");
		}
	}

	private Material material(Object value) {
		if (value == null) {
			return null;
		}
		Material m = Material.matchMaterial(value.toString().trim());
		return m == null || m.isAir() || !m.isItem() ? null : m;
	}

	private Component line(String text) {
		return MM.deserialize("<!italic>" + text);
	}

	private String plain(String key) {
		String k = key.toLowerCase(Locale.ROOT).trim();
		int colon = k.indexOf(58);
		return colon >= 0 ? k.substring(colon + 1) : k;
	}

	private void warn(String id, String message) {
		this.plugin.getLogger().warning("Kit '" + id + "': " + message);
	}

	public record Kit(String id, String displayName, ItemStack[] items, Material icon) {
	}
}