package dk.blockcraft.partysystem.manager;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.manager.KitManager;
import dk.blockcraft.partysystem.model.PartyGroup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class MatchMenu
implements Listener {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private final PartySystem plugin;
	private final NamespacedKey swordKey;
	private static final int S_KIT = 11;
	private static final int S_MODE = 13;
	private static final int S_TEAMS = 15;
	private static final int S_DROP = 21;
	private static final int S_ROUNDS = 23;
	private static final int S_CLOSE = 35;
	private static final int[] S_START = new int[]{30, 31, 32};

	public MatchMenu(PartySystem plugin) {
		this.plugin = plugin;
		this.swordKey = new NamespacedKey(plugin, "party_menu_sword");
	}

	public void giveSword(Player p) {
		for (ItemStack item : p.getInventory().getContents()) {
			if (!this.isSword(item)) continue;
			return;
		}
		ItemStack sword = this.item(Material.DIAMOND_SWORD, "<gold><bold>Party Menu", "<gray>H\u00f8jreklik for at v\u00e6lge gamemode,", "<gray>hold, runder og meget mere.");
		ItemMeta meta = sword.getItemMeta();
		meta.getPersistentDataContainer().set(this.swordKey, PersistentDataType.BYTE, (byte)1);
		sword.setItemMeta(meta);
		for (ItemStack left : p.getInventory().addItem(sword).values()) {
			p.getWorld().dropItemNaturally(p.getLocation(), left);
		}
	}

	public void removeSword(Player p) {
		ItemStack[] contents = p.getInventory().getContents();
		for (int i = 0; i < contents.length; ++i) {
			if (!this.isSword(contents[i])) continue;
			p.getInventory().setItem(i, null);
		}
	}

	private boolean isSword(ItemStack item) {
		if (item == null || !item.hasItemMeta()) {
			return false;
		}
		return item.getItemMeta().getPersistentDataContainer().has(this.swordKey, PersistentDataType.BYTE);
	}

	@EventHandler(priority=EventPriority.LOWEST)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK || !this.isSword(event.getItem())) {
			return;
		}
		event.setCancelled(true);
		Player p = event.getPlayer();
		PartyGroup group = this.plugin.groups().groupOf(p.getUniqueId());
		if (group == null || !group.isLeader(p.getUniqueId())) {
			this.removeSword(p);
			return;
		}
		this.openFromCommand(p);
	}

	public void openFromCommand(Player p) {
		PartyGroup group = this.leaderGroup(p);
		if (group != null) {
			this.openMain(p, group);
		}
	}

	@EventHandler(priority=EventPriority.LOWEST)
	public void onDrop(PlayerDropItemEvent event) {
		if (this.isSword(event.getItemDrop().getItemStack())) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		event.getDrops().removeIf(this::isSword);
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		Player p = event.getPlayer();
		this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
			PartyGroup group = this.plugin.groups().groupOf(p.getUniqueId());
			if (p.isOnline() && group != null && group.isLeader(p.getUniqueId()) && !this.plugin.manager().inParty(p.getUniqueId())) {
				this.giveSword(p);
			}
		});
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof Holder) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		Player p;
		Holder holder;
		block11: {
			block10: {
				InventoryHolder inventoryHolder = event.getView().getTopInventory().getHolder();
				if (!(inventoryHolder instanceof Holder)) {
					return;
				}
				holder = (Holder)inventoryHolder;
				event.setCancelled(true);
				HumanEntity humanEntity = event.getWhoClicked();
				if (!(humanEntity instanceof Player)) break block10;
				p = (Player)humanEntity;
				if (event.getClickedInventory() == event.getView().getTopInventory()) break block11;
			}
			return;
		}
		PartyGroup group = this.leaderGroup(p);
		if (group == null) {
			p.closeInventory();
			return;
		}
		int slot = event.getSlot();
		switch (holder.type.ordinal()) {
			case 0: {
				this.clickMain(p, group, slot, event);
				break;
			}
			case 1: {
				this.clickKits(p, group, slot, event.getView().getTopInventory());
				break;
			}
			case 2: {
				this.clickTeams(p, group, slot, holder);
			}
		}
	}

	private PartyGroup leaderGroup(Player p) {
		PartyGroup group = this.plugin.groups().groupOf(p.getUniqueId());
		if (group == null) {
			this.plugin.messages().send(p, "group-not-in", new TagResolver[0]);
			return null;
		}
		if (!group.isLeader(p.getUniqueId())) {
			this.plugin.messages().send(p, "group-not-leader", new TagResolver[0]);
			return null;
		}
		if (this.plugin.manager().inMatch(p.getUniqueId())) {
			this.plugin.messages().send(p, "match-settings-locked", new TagResolver[0]);
			return null;
		}
		return group;
	}

	private Inventory create(Player p, Type type, int size, String title) {
		Inventory inv;
		Holder holder = new Holder(type);
		holder.inventory = inv = Bukkit.createInventory((InventoryHolder)holder, size, this.c(title));
		p.openInventory(inv);
		return inv;
	}

	private void openMain(Player p, PartyGroup group) {
		Inventory inv = this.create(p, Type.MAIN, 36, "<gold><bold>Party Menu");
		this.fillMain(inv, group);
	}

	private void border(Inventory inv, Material material) {
		ItemStack pane = this.item(material, " ", new String[0]);
		int size = inv.getSize();
		for (int i = 0; i < size; ++i) {
			if (i >= 9 && i < size - 9 && i % 9 != 0 && i % 9 != 8) continue;
			inv.setItem(i, pane);
		}
	}

	private void fillMain(Inventory inv, PartyGroup group) {
		inv.clear();
		KitManager.Kit kit = group.getKit() == null ? null : this.plugin.kits().get(group.getKit());
		String kitName = kit == null ? "<red>Ingen valgt" : kit.displayName();
		String mode = group.isTeams() ? "Hold" : "FFA";
		String drop = group.isDropItems() ? "<green>Ja" : "<red>Nej";
		inv.setItem(4, this.item(Material.NETHER_STAR, "<gold><bold>Dit match", "<gray>Spillere i party: <white>" + group.members().size(), "", "<gray>Kit: <white>" + kitName, "<gray>Mode: <white>" + mode, "<gray>Runder: <white>" + group.getRounds(), "<gray>Items dropper: " + drop));
		inv.setItem(11, this.item(kit == null ? Material.NETHERITE_SWORD : this.iconOf(kit), "<aqua><bold>Gamemode / Kit", "<gray>Valgt: <white>" + kitName, "", "<yellow>Klik for at v\u00e6lge"));
		inv.setItem(13, group.isTeams() ? this.item(Material.RED_BANNER, "<red><bold>Mode: Hold", "<gray>Spillere k\u00e6mper i to hold.", "", "<yellow>Klik for at skifte til FFA") : this.item(Material.IRON_SWORD, "<green><bold>Mode: FFA", "<gray>Alle mod alle.", "", "<yellow>Klik for at skifte til Hold"));
		inv.setItem(15, group.isTeams() ? this.item(Material.BLUE_BANNER, "<blue><bold>Rediger hold", "<gray>V\u00e6lg hvem der er p\u00e5 hvilket hold.", "", "<yellow>Klik for at redigere") : this.item(Material.GRAY_BANNER, "<dark_gray><bold>Rediger hold", "<red>Kun i Hold-mode."));
		inv.setItem(21, group.isDropItems() ? this.item(Material.LIME_WOOL, "<green><bold>Items dropper: JA", "<gray>Ting droppes n\u00e5r man d\u00f8r.", "", "<yellow>Klik for at sl\u00e5 fra") : this.item(Material.RED_WOOL, "<red><bold>Items dropper: NEJ", "<gray>Ting forsvinder n\u00e5r man d\u00f8r.", "", "<yellow>Klik for at sl\u00e5 til"));
		ItemStack rounds = this.item(Material.CLOCK, "<gold><bold>Runder: <white>" + group.getRounds(), "<gray>Venstreklik: <white>+1 <gray>(shift: +5)", "<gray>H\u00f8jreklik: <white>-1 <gray>(shift: -5)");
		rounds.setAmount(Math.min(64, Math.max(1, group.getRounds())));
		inv.setItem(23, rounds);
		ItemStack start = kit == null ? this.item(Material.RED_CONCRETE, "<red><bold>START MATCH", "<gray>V\u00e6lg f\u00f8rst en gamemode / kit.") : this.item(Material.LIME_CONCRETE, "<green><bold>START MATCH", "<gray>Kit: <white>" + kitName, "<gray>Mode: <white>" + mode, "<gray>Runder: <white>" + group.getRounds(), "", "<yellow>Klik for at starte");
		for (int slot : S_START) {
			inv.setItem(slot, start);
		}
		inv.setItem(35, this.item(Material.BARRIER, "<red>Luk", new String[0]));
	}

	private void click(Player p) {
		p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
	}

	private void clickMain(Player p, PartyGroup group, int slot, InventoryClickEvent event) {
		Inventory inv = event.getView().getTopInventory();
		switch (slot) {
			case 11: {
				this.click(p);
				this.openKits(p, group);
				break;
			}
			case 13: {
				this.click(p);
				group.setTeams(!group.isTeams());
				if (group.isTeams()) {
					group.balanceTeams();
				}
				this.fillMain(inv, group);
				break;
			}
			case 15: {
				if (!group.isTeams()) break;
				this.click(p);
				group.balanceTeams();
				this.openTeams(p, group);
				break;
			}
			case 21: {
				this.click(p);
				group.setDropItems(!group.isDropItems());
				this.fillMain(inv, group);
				break;
			}
			case 23: {
				this.click(p);
				int step = event.isShiftClick() ? 5 : 1;
				int max = Math.max(1, this.plugin.settings().config().getInt("match.max-rounds", 20));
				int next = group.getRounds() + (event.isRightClick() ? -step : step);
				group.setRounds(Math.min(max, Math.max(1, next)));
				this.fillMain(inv, group);
				break;
			}
			case 30:
			case 31:
			case 32: {
				if (group.getKit() == null) {
					p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
					return;
				}
				p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
				p.closeInventory();
				this.plugin.manager().startMatch(p, null);
				break;
			}
			case 35: {
				p.closeInventory();
				break;
			}
		}
	}

	private static int kitSlot(int i) {
		return 10 + i / 7 * 9 + i % 7;
	}

	private void openKits(Player p, PartyGroup group) {
		List<KitManager.Kit> kits = this.plugin.kits().all();
		int shown = Math.min(kits.size(), 28);
		int rows = Math.max(1, (shown + 6) / 7);
		int size = (rows + 2) * 9;
		Inventory inv = this.create(p, Type.KITS, size, "<dark_gray><gold><bold>V\u00e6lg gamemode / kit");
		this.border(inv, Material.BLACK_STAINED_GLASS_PANE);
		for (int i = 0; i < shown; ++i) {
			KitManager.Kit kit = kits.get(i);
			boolean selected = kit.id().equals(group.getKit());
			int count = 0;
			for (ItemStack it : kit.items()) {
				if (it == null || it.getType().isAir()) continue;
				++count;
			}
			ItemStack icon = this.item(this.iconOf(kit), (selected ? "<green><bold>" : "<white><bold>") + kit.displayName(), "<dark_gray>\u00b7 <gray>Items: <white>" + count, "", selected ? "<green>Valgt" : "<yellow>\u00bb Klik for at v\u00e6lge");
			if (selected) {
				ItemMeta meta = icon.getItemMeta();
				meta.setEnchantmentGlintOverride(true);
				icon.setItemMeta(meta);
			}
			inv.setItem(MatchMenu.kitSlot(i), icon);
		}
		inv.setItem(size - 5, this.item(Material.ARROW, "<red>\u00ab Tilbage", new String[0]));
	}

	private void clickKits(Player p, PartyGroup group, int slot, Inventory inv) {
		if (slot == inv.getSize() - 5) {
			this.click(p);
			this.openMain(p, group);
			return;
		}
		List<KitManager.Kit> kits = this.plugin.kits().all();
		int shown = Math.min(kits.size(), 28);
		for (int i = 0; i < shown; ++i) {
			if (MatchMenu.kitSlot(i) != slot) continue;
			this.click(p);
			group.setKit(kits.get(i).id());
			this.openMain(p, group);
			return;
		}
	}

	private Material iconOf(KitManager.Kit kit) {
		if (kit.icon() != null) {
			return kit.icon();
		}
		for (ItemStack item : kit.items()) {
			if (item == null || item.getType().isAir()) continue;
			return item.getType();
		}
		return Material.IRON_SWORD;
	}

	private void openTeams(Player p, PartyGroup group) {
		Inventory inv;
		Holder holder = new Holder(Type.TEAMS);
		holder.inventory = inv = Bukkit.createInventory((InventoryHolder)holder, 54, this.c("<dark_gray><gold><bold>Rediger hold"));
		this.fillTeams(inv, group, holder);
		p.openInventory(inv);
	}

	private void fillTeams(Inventory inv, PartyGroup group, Holder holder) {
		inv.clear();
		holder.slots.clear();
		this.border(inv, Material.BLACK_STAINED_GLASS_PANE);
		ArrayList<UUID> red = new ArrayList<UUID>();
		ArrayList<UUID> blue = new ArrayList<UUID>();
		for (UUID id : group.members()) {
			(group.team(id) == 1 ? blue : red).add(id);
		}
		inv.setItem(4, this.item(Material.RED_BANNER, "<red><bold>R\u00f8dt hold <gray>(" + red.size() + ")", new String[0]));
		inv.setItem(22, this.item(Material.BLUE_BANNER, "<blue><bold>Bl\u00e5t hold <gray>(" + blue.size() + ")", new String[0]));
		this.placeHeads(inv, holder, red, 10, false);
		this.placeHeads(inv, holder, blue, 28, true);
		inv.setItem(47, this.item(Material.ENDER_EYE, "<light_purple>Bland hold", "<gray>Fordel alle tilf\u00e6ldigt"));
		inv.setItem(49, this.item(Material.ARROW, "<red>\u00ab Tilbage", new String[0]));
	}

	private void placeHeads(Inventory inv, Holder holder, List<UUID> ids, int start, boolean blue) {
		for (int i = 0; i < ids.size() && i < 7; ++i) {
			UUID id = ids.get(i);
			String name = String.valueOf(Bukkit.getOfflinePlayer(id).getName());
			ItemStack head = new ItemStack(Material.PLAYER_HEAD);
			SkullMeta meta = (SkullMeta)head.getItemMeta();
			meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
			meta.displayName(this.c((blue ? "<blue>" : "<red>") + "<bold>" + name));
			meta.lore(List.of(this.c(blue ? "<blue>Hold: Bl\u00e5" : "<red>Hold: R\u00f8d"), this.c(""), this.c("<yellow>\u00bb Klik for at skifte hold")));
			head.setItemMeta(meta);
			inv.setItem(start + i, head);
			holder.slots.put(start + i, id);
		}
	}

	private void clickTeams(Player p, PartyGroup group, int slot, Holder holder) {
		Inventory inv = holder.inventory;
		if (slot == 49) {
			this.click(p);
			this.openMain(p, group);
			return;
		}
		if (slot == 47) {
			this.click(p);
			group.shuffleTeams();
			this.fillTeams(inv, group, holder);
			return;
		}
		UUID id = holder.slots.get(slot);
		if (id != null) {
			this.click(p);
			group.setTeam(id, group.team(id) == 0 ? 1 : 0);
			this.fillTeams(inv, group, holder);
		}
	}

	private Component c(String text) {
		return MM.deserialize("<!italic>" + text);
	}

	private ItemStack item(Material material, String name, String ... lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(this.c(name));
		meta.addItemFlags(ItemFlag.values());
		if (lore.length > 0) {
			ArrayList<Component> lines = new ArrayList<Component>();
			for (String line : lore) {
				lines.add(this.c(line));
			}
			meta.lore(lines);
		}
		stack.setItemMeta(meta);
		return stack;
	}

	private static final class Holder
	implements InventoryHolder {
		private final Type type;
		private Inventory inventory;
		private final Map<Integer, UUID> slots = new HashMap<Integer, UUID>();

		private Holder(Type type) {
			this.type = type;
		}

		@Override
		public Inventory getInventory() {
			return this.inventory;
		}
	}

	private static enum Type {
		MAIN,
		KITS,
		TEAMS;

	}
}