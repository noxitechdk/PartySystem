package dk.blockcraft.partysystem.listener;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.manager.PartyManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

public final class PartyListener
implements Listener {
	private final PartySystem plugin;
	private final Map<UUID, Set<Location>> placed = new HashMap<UUID, Set<Location>>();
	private final Map<UUID, Set<UUID>> dropped = new HashMap<UUID, Set<UUID>>();
	private final NamespacedKey dropKey;

	public PartyListener(PartySystem plugin) {
		this.plugin = plugin;
		this.dropKey = new NamespacedKey(plugin, "party_drop");
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		this.plugin.stats().load(uuid, event.getPlayer().getName());
		this.plugin.dataManager().load(uuid).whenComplete((data, error) -> {
			if (!this.plugin.isEnabled()) {
				return;
			}
			this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
				Player player = this.plugin.getServer().getPlayer(uuid);
				if (player == null || !player.isOnline()) {
					this.plugin.dataManager().unload(uuid);
					return;
				}
				if (error != null) {
					this.plugin.getLogger().log(Level.SEVERE, "Kunne ikke indl\u00e6se data for " + player.getName() + ": " + error.getMessage(), (Throwable)error);
					this.plugin.messages().send(player, "data-load-failed", new TagResolver[0]);
					return;
				}
				this.plugin.manager().recover(player);
			});
		});
	}

	@EventHandler(priority=EventPriority.LOWEST)
	public void onQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		this.clearPlaced(player.getUniqueId());
		this.clearDropped(player.getUniqueId());
		this.plugin.queue().remove(player.getUniqueId());
		this.plugin.groups().handleQuit(player);
		this.plugin.manager().leaveQuietly(player);
		this.plugin.menu().removeSword(player);
		this.plugin.editor().handleQuit(player);
		this.plugin.dataManager().unload(player.getUniqueId());
		this.plugin.stats().unload(player.getUniqueId());
	}

	@EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
	public void onCommand(PlayerCommandPreprocessEvent event) {
		Player player = event.getPlayer();
		if (!this.plugin.manager().inParty(player.getUniqueId()) || player.hasPermission("partysystem.command.bypass")) {
			return;
		}
		String text = event.getMessage();
		String label = text.length() > 1 ? text.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT) : "";
		int colon = label.indexOf(58);
		if (colon >= 0) {
			label = label.substring(colon + 1);
		}
		for (String allowed : this.plugin.settings().config().getStringList("commands.whitelist")) {
			if (!allowed.equalsIgnoreCase(label)) continue;
			return;
		}
		event.setCancelled(true);
		this.plugin.messages().send(player, "command-blocked", new TagResolver[0]);
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		if (!this.plugin.manager().inParty(player.getUniqueId())) {
			return;
		}
		UUID id = player.getUniqueId();
		if (this.plugin.manager().inMatch(id)) {
			ArrayList<ItemStack> items = new ArrayList<ItemStack>(event.getDrops());
			event.getDrops().clear();
			event.setDroppedExp(0);
			if (this.plugin.manager().matchDropsItems(id)) {
				this.dropTagged(player, items);
			}
		} else if (this.plugin.settings().config().getBoolean("death.clear-drops", true)) {
			event.getDrops().clear();
			event.setDroppedExp(0);
		}
		event.setKeepInventory(false);
		event.setKeepLevel(false);
		this.plugin.manager().matchDeath(player, player.getKiller());
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		if (!this.plugin.manager().inParty(player.getUniqueId())) {
			return;
		}
		Location spawn = this.plugin.manager().arenaSpawn(player.getUniqueId());
		if (spawn != null) {
			event.setRespawnLocation(spawn);
		}
		this.plugin.manager().matchRespawn(player);
		if (this.plugin.settings().config().getBoolean("death.give-kit-on-respawn", true)) {
			this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
				if (player.isOnline()) {
					this.plugin.manager().giveKitOnRespawn(player);
				}
			});
		}
	}

	@EventHandler(priority=EventPriority.HIGH)
	public void onDamage(EntityDamageByEntityEvent event) {
		Entity entity;
		Player attacker = this.attackerOf(event.getDamager());
		if (attacker == null || !((entity = event.getEntity()) instanceof Player)) {
			return;
		}
		Player victim = (Player)entity;
		UUID a = attacker.getUniqueId();
		UUID v = victim.getUniqueId();
		PartyManager manager = this.plugin.manager();
		boolean attackerIn = manager.inParty(a);
		boolean victimIn = manager.inParty(v);
		if (!attackerIn && !victimIn) {
			return;
		}
		Object deny = null;
		if (!attackerIn || !victimIn) {
			deny = "kun en af spillerne er i party";
		} else if (!manager.canFight(a)) {
			deny = attacker.getName() + " kan ikke k\u00e6mpe (nedt\u00e6lling k\u00f8rer, eller runden er ikke startet)";
		} else if (!manager.canFight(v)) {
			deny = victim.getName() + " kan ikke sl\u00e5s (nedt\u00e6lling k\u00f8rer, eller runden er ikke startet)";
		} else if (manager.inBuilder(a) || manager.inBuilder(v)) {
			deny = "en af spillerne er i builder";
		} else if (manager.sameTeam(a, v)) {
			deny = "samme hold";
		} else if (!this.plugin.settings().config().getBoolean("kit-mode.allow-pvp", true)) {
			deny = "kit-mode.allow-pvp er false i config.yml";
		}
		if (deny != null) {
			event.setCancelled(true);
			manager.debug("Slag " + attacker.getName() + " -> " + victim.getName() + " afvist: " + (String)deny);
			return;
		}
		if (event.isCancelled() && this.plugin.settings().config().getBoolean("kit-mode.override-protection", true)) {
			event.setCancelled(false);
			manager.debug("Slag " + attacker.getName() + " -> " + victim.getName() + " var annulleret af en anden plugin/verdensregel, men er tilladt af party.");
		}
	}

	@EventHandler(ignoreCancelled=true)
	public void onBlockPlace(BlockPlaceEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		if (!this.plugin.manager().inParty(uuid)) {
			return;
		}
		YamlConfiguration config = this.plugin.settings().config();
		if (this.plugin.manager().inBuilder(uuid)) {
			event.setCancelled(true);
			return;
		}
		if (config.getBoolean("kit-mode.allow-build", false)) {
			return;
		}
		if (!config.getBoolean("kit-mode.allow-place", true)) {
			event.setCancelled(true);
			return;
		}
		this.placed.computeIfAbsent(uuid, k -> new HashSet()).add(event.getBlockPlaced().getLocation());
	}

	@EventHandler(ignoreCancelled=true)
	public void onBlockBreak(BlockBreakEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		if (!this.plugin.manager().inParty(uuid)) {
			return;
		}
		if (this.plugin.manager().inBuilder(uuid)) {
			event.setCancelled(true);
			return;
		}
		if (this.plugin.settings().config().getBoolean("kit-mode.allow-build", false)) {
			return;
		}
		Set<Location> own = this.placed.get(uuid);
		if (own == null || !own.remove(event.getBlock().getLocation())) {
			event.setCancelled(true);
		}
	}

	public void clearPlaced(UUID uuid) {
		Set<Location> own = this.placed.remove(uuid);
		if (own == null) {
			return;
		}
		for (Location loc : own) {
			loc.getBlock().setType(Material.AIR, false);
		}
	}

	public void clearAllPlaced() {
		for (UUID uuid : new ArrayList<UUID>(this.placed.keySet())) {
			this.clearPlaced(uuid);
		}
		for (UUID uuid : new ArrayList<UUID>(this.dropped.keySet())) {
			this.clearDropped(uuid);
		}
	}

	@EventHandler(ignoreCancelled=true)
	public void onBucketEmpty(PlayerBucketEmptyEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		if (!this.plugin.manager().inParty(uuid)) {
			return;
		}
		YamlConfiguration config = this.plugin.settings().config();
		if (this.plugin.manager().inBuilder(uuid)) {
			event.setCancelled(true);
			return;
		}
		if (config.getBoolean("kit-mode.allow-build", false)) {
			return;
		}
		if (!config.getBoolean("kit-mode.allow-place", true)) {
			event.setCancelled(true);
			return;
		}
		this.placed.computeIfAbsent(uuid, k -> new HashSet()).add(event.getBlock().getLocation());
	}

	@EventHandler(ignoreCancelled=true)
	public void onBucketFill(PlayerBucketFillEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		if (!this.plugin.manager().inParty(uuid)) {
			return;
		}
		if (this.plugin.manager().inBuilder(uuid)) {
			event.setCancelled(true);
			return;
		}
		if (this.plugin.settings().config().getBoolean("kit-mode.allow-build", false)) {
			return;
		}
		Set<Location> own = this.placed.get(uuid);
		if (own == null || !own.remove(event.getBlock().getLocation())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled=true)
	public void onDrop(PlayerDropItemEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		if (!this.plugin.manager().inParty(uuid)) {
			return;
		}
		if (this.plugin.manager().inBuilder(uuid) || !this.plugin.settings().config().getBoolean("kit-mode.allow-item-drop", true)) {
			event.setCancelled(true);
			return;
		}
		Item item = event.getItemDrop();
		item.getPersistentDataContainer().set(this.dropKey, PersistentDataType.STRING, uuid.toString());
		this.dropped.computeIfAbsent(uuid, k -> new HashSet()).add(item.getUniqueId());
	}

	private boolean isPartyDrop(Item item) {
		return item.getPersistentDataContainer().has(this.dropKey, PersistentDataType.STRING);
	}

	@EventHandler(ignoreCancelled=true)
	public void onPickup(EntityPickupItemEvent event) {
		block5: {
			block4: {
				if (!this.isPartyDrop(event.getItem())) {
					return;
				}
				LivingEntity livingEntity = event.getEntity();
				if (!(livingEntity instanceof Player)) break block4;
				Player player = (Player)livingEntity;
				if (this.plugin.manager().inParty(player.getUniqueId()) && !this.plugin.manager().inBuilder(player.getUniqueId())) break block5;
			}
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled=true)
	public void onHopperPickup(InventoryPickupItemEvent event) {
		if (this.isPartyDrop(event.getItem())) {
			event.setCancelled(true);
		}
	}

	private void dropTagged(Player player, List<ItemStack> items) {
		Location loc = player.getLocation();
		Set ids = this.dropped.computeIfAbsent(player.getUniqueId(), k -> new HashSet());
		for (ItemStack stack : items) {
			if (stack == null || stack.getType().isAir()) continue;
			Item item = loc.getWorld().dropItemNaturally(loc, stack);
			item.getPersistentDataContainer().set(this.dropKey, PersistentDataType.STRING, player.getUniqueId().toString());
			ids.add(item.getUniqueId());
		}
	}

	public void clearDropped(UUID uuid) {
		Set<UUID> ids = this.dropped.remove(uuid);
		if (ids == null) {
			return;
		}
		for (UUID id : ids) {
			Item item;
			Entity entity = this.plugin.getServer().getEntity(id);
			if (!(entity instanceof Item) || !this.isPartyDrop(item = (Item)entity)) continue;
			item.remove();
		}
	}

	private Player attackerOf(Entity damager) {
		Projectile projectile;
		ProjectileSource projectileSource;
		if (damager instanceof Player) {
			Player player = (Player)damager;
			return player;
		}
		if (damager instanceof Projectile && (projectileSource = (projectile = (Projectile)damager).getShooter()) instanceof Player) {
			Player shooter = (Player)projectileSource;
			return shooter;
		}
		return null;
	}
}