package dk.blockcraft.partysystem.manager;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.manager.StatsManager;
import dk.blockcraft.partysystem.model.PartyStats;
import dk.blockcraft.partysystem.util.Messages;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class HologramManager
implements Listener {
	private static final String TAG = "partysystem_hologram";
	private final PartySystem plugin;
	private final File file;
	private final Map<String, Holo> holos = new LinkedHashMap<String, Holo>();
	private final Map<String, UUID> entities = new LinkedHashMap<String, UUID>();

	public HologramManager(PartySystem plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "holograms.yml");
		this.load();
	}

	public void start() {
		this.cleanupOrphans();
		this.updateAll();
	}

	public void shutdown() {
		for (String id : new ArrayList<String>(this.entities.keySet())) {
			this.removeEntity(id);
		}
		this.entities.clear();
	}

	private void load() {
		this.holos.clear();
		if (!this.file.exists()) {
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file);
		ConfigurationSection root = yaml.getConfigurationSection("holograms");
		if (root == null) {
			return;
		}
		for (String id : root.getKeys(false)) {
			ConfigurationSection s = root.getConfigurationSection(id);
			if (s == null) continue;
			StatsManager.Top type = StatsManager.Top.parse(s.getString("type", "elo"));
			String world = s.getString("world");
			if (type == null || world == null) {
				this.plugin.getLogger().warning("Ugyldigt hologram '" + id + "' i holograms.yml blev sprunget over.");
				continue;
			}
			this.holos.put(id, new Holo(id, type, world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z")));
		}
	}

	private void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Holo h : this.holos.values()) {
			String base = "holograms." + h.id();
			yaml.set(base + ".type", h.type().id());
			yaml.set(base + ".world", h.world());
			yaml.set(base + ".x", h.x());
			yaml.set(base + ".y", h.y());
			yaml.set(base + ".z", h.z());
		}
		try {
			yaml.save(this.file);
		}
		catch (IOException ex) {
			this.plugin.getLogger().log(Level.SEVERE, "Kunne ikke gemme holograms.yml: " + ex.getMessage(), ex);
		}
	}

	public Collection<Holo> all() {
		return List.copyOf(this.holos.values());
	}

	public Holo get(String id) {
		return id == null ? null : this.holos.get(id.toLowerCase(Locale.ROOT));
	}

	public List<String> ids() {
		return new ArrayList<String>(this.holos.keySet());
	}

	public String create(Location where, StatsManager.Top type) {
		int n = 1;
		while (this.holos.containsKey(type.id() + "-" + n)) {
			++n;
		}
		String id = type.id() + "-" + n;
		World world = where.getWorld();
		this.holos.put(id, new Holo(id, type, world == null ? "" : world.getName(), where.getX(), where.getY(), where.getZ()));
		this.save();
		this.update(this.holos.get(id));
		return id;
	}

	public boolean remove(String id) {
		Holo h = this.get(id);
		if (h == null) {
			return false;
		}
		this.holos.remove(h.id());
		this.removeEntity(h.id());
		this.save();
		return true;
	}

	public boolean move(String id, Location where) {
		Holo h = this.get(id);
		if (h == null || where.getWorld() == null) {
			return false;
		}
		this.removeEntity(h.id());
		Holo moved = new Holo(h.id(), h.type(), where.getWorld().getName(), where.getX(), where.getY(), where.getZ());
		this.holos.put(h.id(), moved);
		this.save();
		this.update(moved);
		return true;
	}

	public void reload() {
		this.load();
		this.shutdown();
		this.cleanupOrphans();
		this.updateAll();
		this.plugin.stats().refreshTops();
	}

	public void updateAll() {
		for (Holo h : this.holos.values()) {
			try {
				this.update(h);
			}
			catch (RuntimeException ex) {
				this.plugin.getLogger().log(Level.WARNING, "Kunne ikke opdatere hologram " + h.id() + ": " + ex.getMessage(), ex);
			}
		}
	}

	private void update(Holo h) {
		World world = Bukkit.getWorld(h.world());
		if (world == null) {
			return;
		}
		if (!world.isChunkLoaded((int)Math.floor(h.x()) >> 4, (int)Math.floor(h.z()) >> 4)) {
			return;
		}
		TextDisplay display = this.existing(h.id());
		if (display == null) {
			display = this.spawn(world, h);
		}
		if (display != null) {
			display.text(this.render(h));
		}
	}

	private TextDisplay existing(String id) {
		TextDisplay display;
		UUID uuid = this.entities.get(id);
		if (uuid == null) {
			return null;
		}
		Entity e = Bukkit.getEntity(uuid);
		if (e instanceof TextDisplay && (display = (TextDisplay)e).isValid()) {
			return display;
		}
		this.entities.remove(id);
		return null;
	}

	private void removeEntity(String id) {
		UUID uuid = this.entities.remove(id);
		if (uuid == null) {
			return;
		}
		Entity e = Bukkit.getEntity(uuid);
		if (e != null) {
			e.remove();
		}
	}

	private void cleanupOrphans() {
		HashSet<UUID> known = new HashSet<UUID>(this.entities.values());
		for (World world : Bukkit.getWorlds()) {
			for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
				if (!display.getScoreboardTags().contains(TAG) || known.contains(display.getUniqueId())) continue;
				display.remove();
			}
		}
	}

	private TextDisplay spawn(World world, Holo h) {
		YamlConfiguration cfg = this.plugin.settings().config();
		Location where = new Location(world, h.x(), h.y() + cfg.getDouble("leaderboard.y-offset", 0.5), h.z());
		Display.Billboard billboard = Display.Billboard.CENTER;
		try {
			billboard = Display.Billboard.valueOf(cfg.getString("leaderboard.billboard", "CENTER").toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			this.plugin.getLogger().warning("Ugyldig leaderboard.billboard i config.yml (brug FIXED, VERTICAL, HORIZONTAL eller CENTER).");
		}
		Display.Billboard mode = billboard;
		boolean shadow = cfg.getBoolean("leaderboard.shadow", true);
		float viewRange = (float)Math.max(0.1, cfg.getDouble("leaderboard.view-range", 1.0));
		int lineWidth = Math.max(20, cfg.getInt("leaderboard.line-width", 200));
		float scale = (float)Math.max(0.1, cfg.getDouble("leaderboard.scale", 1.0));
		String background = cfg.getString("leaderboard.background", "transparent");
		TextDisplay display = world.spawn(where, TextDisplay.class, td -> {
			td.setPersistent(false);
			td.addScoreboardTag(TAG);
			td.setBillboard(mode);
			td.setAlignment(TextDisplay.TextAlignment.CENTER);
			td.setShadowed(shadow);
			td.setSeeThrough(false);
			td.setViewRange(viewRange);
			td.setLineWidth(lineWidth);
			this.applyBackground((TextDisplay)td, background);
			if (scale != 1.0f) {
				td.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
			}
		});
		this.entities.put(h.id(), display.getUniqueId());
		return display;
	}

	private void applyBackground(TextDisplay td, String value) {
		String v;
		String string = v = value == null ? "transparent" : value.trim();
		if (v.equalsIgnoreCase("default")) {
			td.setDefaultBackground(true);
			return;
		}
		td.setDefaultBackground(false);
		if (v.equalsIgnoreCase("transparent") || v.isEmpty()) {
			td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
			return;
		}
		try {
			String hex = v.startsWith("#") ? v.substring(1) : v;
			long parsed = Long.parseLong(hex, 16);
			if (hex.length() == 8) {
				td.setBackgroundColor(Color.fromARGB((int)(parsed >> 24 & 0xFFL), (int)(parsed >> 16 & 0xFFL), (int)(parsed >> 8 & 0xFFL), (int)(parsed & 0xFFL)));
			} else {
				td.setBackgroundColor(Color.fromARGB(160, (int)(parsed >> 16 & 0xFFL), (int)(parsed >> 8 & 0xFFL), (int)(parsed & 0xFFL)));
			}
		}
		catch (NumberFormatException ex) {
			this.plugin.getLogger().warning("Ugyldig leaderboard.background i config.yml: " + value);
			td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
		}
	}

	private Component render(Holo h) {
		YamlConfiguration cfg = this.plugin.settings().config();
		StatsManager.Top type = h.type();
		String base = "leaderboard.types." + type.id();
		int entries = Math.max(1, Math.min(50, cfg.getInt("leaderboard.entries", 10)));
		long refresh = Math.max(5L, cfg.getLong("leaderboard.refresh-seconds", 30L));
		String line = cfg.getString(base + ".line", "<gray>#<position> <white><player> <yellow><value>");
		String empty = cfg.getString(base + ".empty-line", "<gray>#<position> <dark_gray>---");
		List<PartyStats> top = this.plugin.stats().top(type);
		Messages messages = this.plugin.messages();
		TagResolver[] common = new TagResolver[]{Messages.tag("type", type.id()), Messages.tag("entries", entries), Messages.tag("refresh", refresh)};
		ArrayList<Component> lines = new ArrayList<Component>();
		for (String text : cfg.getStringList(base + ".header")) {
			lines.add(messages.format(text, common));
		}
		for (int i = 0; i < entries; ++i) {
			int position = i + 1;
			if (i < top.size()) {
				PartyStats s = top.get(i);
				String raw = cfg.getString(base + ".line-" + position, line);
				ArrayList<TagResolver> tags = new ArrayList<TagResolver>(List.of(StatsManager.tags(s)));
				tags.add(Messages.tag("position", position));
				tags.add(Messages.tag("value", type.value(s)));
				tags.addAll(List.of(common));
				lines.add(messages.format(raw, tags.toArray(new TagResolver[0])));
				continue;
			}
			String raw = cfg.getString(base + ".empty-line-" + position, empty);
			lines.add(messages.format(raw, Messages.tag("position", position), common[0], common[1], common[2]));
		}
		for (String text : cfg.getStringList(base + ".footer")) {
			lines.add(messages.format(text, common));
		}
		return Component.join(JoinConfiguration.newlines(), lines);
	}

	@EventHandler
	public void onChunkLoad(ChunkLoadEvent event) {
		World world = event.getWorld();
		int cx = event.getChunk().getX();
		int cz = event.getChunk().getZ();
		for (Holo h : this.holos.values()) {
			if (!h.world().equals(world.getName()) || (int)Math.floor(h.x()) >> 4 != cx || (int)Math.floor(h.z()) >> 4 != cz) continue;
			Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
				if (this.holos.containsKey(h.id())) {
					this.update(h);
				}
			});
		}
	}

	public record Holo(String id, StatsManager.Top type, String world, double x, double y, double z) {
	}
}