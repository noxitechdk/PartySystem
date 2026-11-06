package dk.blockcraft.partysystem.model;

import dk.blockcraft.partysystem.util.InventoryUtil;
import dk.blockcraft.partysystem.util.ItemCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

public final class OriginalState {
	private final ItemStack[] items;
	private final int level;
	private final float exp;
	private final double health;
	private final int food;
	private final float saturation;
	private final GameMode gameMode;
	private final Location location;
	private final List<PotionEffect> effects;

	private OriginalState(ItemStack[] items, int level, float exp, double health, int food, float saturation, GameMode gameMode, Location location, List<PotionEffect> effects) {
		this.items = items;
		this.level = level;
		this.exp = exp;
		this.health = health;
		this.food = food;
		this.saturation = saturation;
		this.gameMode = gameMode;
		this.location = location;
		this.effects = effects;
	}

	public static OriginalState capture(Player p) {
		return new OriginalState(InventoryUtil.snapshot(p.getInventory()), p.getLevel(), p.getExp(), p.getHealth(), p.getFoodLevel(), p.getSaturation(), p.getGameMode(), p.getLocation().clone(), List.copyOf(p.getActivePotionEffects()));
	}

	public void apply(Player p) {
		InventoryUtil.apply(p.getInventory(), this.items);
		for (PotionEffect effect : p.getActivePotionEffects()) {
			p.removePotionEffect(effect.getType());
		}
		p.addPotionEffects(this.effects);
		p.setLevel(this.level);
		p.setExp(this.exp);
		double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
		p.setHealth(Math.max(1.0, Math.min(this.health, max)));
		p.setFoodLevel(this.food);
		p.setSaturation(this.saturation);
		p.setFireTicks(0);
		p.setFallDistance(0.0f);
	}

	public GameMode gameMode() {
		return this.gameMode;
	}

	public Location location() {
		return this.location;
	}

	public String toYaml() {
		YamlConfiguration y = new YamlConfiguration();
		y.set("items", Base64.getEncoder().encodeToString(ItemCodec.encode(this.items)));
		y.set("level", this.level);
		y.set("exp", Float.valueOf(this.exp));
		y.set("health", this.health);
		y.set("food", this.food);
		y.set("saturation", Float.valueOf(this.saturation));
		y.set("gamemode", this.gameMode.name());
		y.set("effects", this.effects);
		if (this.location != null && this.location.getWorld() != null) {
			y.set("world", this.location.getWorld().getUID().toString());
			y.set("x", this.location.getX());
			y.set("y", this.location.getY());
			y.set("z", this.location.getZ());
			y.set("yaw", Float.valueOf(this.location.getYaw()));
			y.set("pitch", Float.valueOf(this.location.getPitch()));
		}
		return y.saveToString();
	}

	public static OriginalState fromYaml(String yaml) throws InvalidConfigurationException {
		GameMode mode;
		YamlConfiguration y = new YamlConfiguration();
		y.loadFromString(yaml);
		ItemStack[] decoded = ItemCodec.decode(Base64.getDecoder().decode(y.getString("items", "")));
		ItemStack[] items = Arrays.copyOf(decoded, 41);
		ArrayList<PotionEffect> effects = new ArrayList<PotionEffect>();
		for (Object o : y.getList("effects", List.of())) {
			if (!(o instanceof PotionEffect)) continue;
			PotionEffect effect = (PotionEffect)o;
			effects.add(effect);
		}
		Location loc = null;
		try {
			World w = Bukkit.getWorld(UUID.fromString(y.getString("world", "")));
			if (w != null) {
				loc = new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"), (float)y.getDouble("yaw"), (float)y.getDouble("pitch"));
			}
		}
		catch (IllegalArgumentException w) {
		}
		try {
			mode = GameMode.valueOf(y.getString("gamemode", "SURVIVAL"));
		}
		catch (IllegalArgumentException ex) {
			mode = GameMode.SURVIVAL;
		}
		return new OriginalState(items, y.getInt("level"), (float)y.getDouble("exp"), y.getDouble("health", 20.0), y.getInt("food", 20), (float)y.getDouble("saturation", 5.0), mode, loc, effects);
	}
}