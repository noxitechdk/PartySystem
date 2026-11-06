package dk.blockcraft.partysystem.data;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.model.Point;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

public final class LocationStore {
	public static final String DEFAULT_ARENA = "default";
	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
	private final PartySystem plugin;
	private final File file;
	private final Object fileLock = new Object();
	private final Map<String, Arena> arenas = new ConcurrentHashMap<String, Arena>();
	private volatile boolean open = true;

	public LocationStore(PartySystem plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "locations.yml");
		if (this.file.exists()) {
			YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file);
			this.open = yaml.getBoolean("open", true);
			ConfigurationSection section = yaml.getConfigurationSection("arenas");
			if (section != null) {
				for (String name : section.getKeys(false)) {
					ConfigurationSection a = section.getConfigurationSection(name);
					if (a == null) continue;
					Arena arena = new Arena(name.toLowerCase(Locale.ROOT));
					arena.spawn = LocationStore.read(a.getConfigurationSection("spawn"));
					arena.pos1 = LocationStore.read(a.getConfigurationSection("pos1"));
					arena.pos2 = LocationStore.read(a.getConfigurationSection("pos2"));
					for (String k : a.getStringList("kits")) {
						arena.kits.add(k.toLowerCase(Locale.ROOT));
					}
					this.arenas.put(arena.name, arena);
				}
			} else if (yaml.getConfigurationSection("spawn") != null) {
				Arena arena = new Arena(DEFAULT_ARENA);
				arena.spawn = LocationStore.read(yaml.getConfigurationSection("spawn"));
				arena.pos1 = LocationStore.read(yaml.getConfigurationSection("pos1"));
				arena.pos2 = LocationStore.read(yaml.getConfigurationSection("pos2"));
				this.arenas.put(arena.name, arena);
			}
		}
	}

	public boolean isOpen() {
		return this.open;
	}

	public void setOpen(boolean open) {
		this.open = open;
		this.saveAsync();
	}

	public static boolean validName(String name) {
		return name != null && NAME.matcher(name).matches();
	}

	public static String normalize(String name) {
		return name == null ? DEFAULT_ARENA : name.toLowerCase(Locale.ROOT);
	}

	public List<String> arenaNames() {
		ArrayList<String> names = new ArrayList<String>(this.arenas.keySet());
		Collections.sort(names);
		return names;
	}

	public Arena arena(String name) {
		return name == null ? null : this.arenas.get(LocationStore.normalize(name));
	}

	public List<String> spawnableArenas() {
		ArrayList<String> out = new ArrayList<String>();
		for (Arena a : this.arenas.values()) {
			Point p = a.spawn;
			if (p == null || p.toLocation() == null) continue;
			out.add(a.name);
		}
		Collections.sort(out);
		return out;
	}

	public Location spawnOf(String arena) {
		Arena a = this.arena(arena);
		if (a == null || a.spawn == null) {
			return null;
		}
		return a.spawn.toLocation();
	}

	public String pickArena(Function<String, Integer> load) {
		List<String> candidates = this.spawnableArenas();
		if (candidates.isEmpty()) {
			return null;
		}
		int best = Integer.MAX_VALUE;
		ArrayList<String> lowest = new ArrayList<String>();
		for (String name : candidates) {
			int n = load.apply(name);
			if (n < best) {
				best = n;
				lowest.clear();
			}
			if (n != best) continue;
			lowest.add(name);
		}
		return (String)lowest.get(ThreadLocalRandom.current().nextInt(lowest.size()));
	}

	public List<String> freeArenasFor(String kitId, Predicate<String> busy) {
		ArrayList<String> out = new ArrayList<String>();
		for (String name : this.spawnableArenas()) {
			Arena a = this.arenas.get(name);
			if (a == null || !a.allows(kitId) || busy.test(name)) continue;
			out.add(name);
		}
		return out;
	}

	public List<String> arenasFor(String kitId) {
		return this.freeArenasFor(kitId, n -> false);
	}

	public String pickFreeArena(String kitId, Predicate<String> busy) {
		List<String> free = this.freeArenasFor(kitId, busy);
		return free.isEmpty() ? null : free.get(ThreadLocalRandom.current().nextInt(free.size()));
	}

	public boolean editKits(String arena, String action, String kitId) {
		Arena a = this.arena(arena);
		if (a == null) {
			return false;
		}
		switch (action) {
			case "add": {
				a.kits.add(kitId.toLowerCase(Locale.ROOT));
				break;
			}
			case "remove": {
				a.kits.remove(kitId.toLowerCase(Locale.ROOT));
				break;
			}
			default: {
				a.kits.clear();
			}
		}
		this.saveAsync();
		return true;
	}

	private Arena getOrCreate(String name) {
		return this.arenas.computeIfAbsent(LocationStore.normalize(name), Arena::new);
	}

	public void setSpawn(String arena, Location loc) {
		this.getOrCreate((String)arena).spawn = Point.of(loc);
		this.saveAsync();
	}

	public void setPos1(String arena, Location loc) {
		this.getOrCreate((String)arena).pos1 = Point.of(loc);
		this.saveAsync();
	}

	public void setPos2(String arena, Location loc) {
		this.getOrCreate((String)arena).pos2 = Point.of(loc);
		this.saveAsync();
	}

	public boolean removeArena(String arena) {
		boolean removed;
		boolean bl = removed = this.arenas.remove(LocationStore.normalize(arena)) != null;
		if (removed) {
			this.saveAsync();
		}
		return removed;
	}

	public boolean hasRegion(String arena) {
		Arena a = this.arena(arena);
		return a != null && a.hasRegion();
	}

	public boolean inRegion(String arena, Location loc) {
		Arena ar = this.arena(arena);
		if (ar == null || !ar.hasRegion()) {
			return true;
		}
		Point a = ar.pos1;
		Point b = ar.pos2;
		if (loc.getWorld() == null || !loc.getWorld().getName().equals(a.world())) {
			return false;
		}
		return loc.getX() >= Math.min(a.x(), b.x()) - 1.0 && loc.getX() <= Math.max(a.x(), b.x()) + 2.0 && loc.getZ() >= Math.min(a.z(), b.z()) - 1.0 && loc.getZ() <= Math.max(a.z(), b.z()) + 2.0;
	}

	private static Point read(ConfigurationSection s) {
		if (s == null || s.getString("world") == null) {
			return null;
		}
		return new Point(s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float)s.getDouble("yaw"), (float)s.getDouble("pitch"));
	}

	private static void write(YamlConfiguration yaml, String path, Point p) {
		if (p == null) {
			return;
		}
		yaml.set(path + ".world", p.world());
		yaml.set(path + ".x", p.x());
		yaml.set(path + ".y", p.y());
		yaml.set(path + ".z", p.z());
		yaml.set(path + ".yaw", Float.valueOf(p.yaw()));
		yaml.set(path + ".pitch", Float.valueOf(p.pitch()));
	}

	private void saveAsync() {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.set("open", this.open);
		for (Arena a : this.arenas.values()) {
			String base = "arenas." + a.name;
			LocationStore.write(yaml, base + ".spawn", a.spawn);
			LocationStore.write(yaml, base + ".pos1", a.pos1);
			LocationStore.write(yaml, base + ".pos2", a.pos2);
			if (a.kits.isEmpty()) continue;
			yaml.set(base + ".kits", a.kits());
		}
		String data = yaml.saveToString();
		Runnable task = () -> {
			Object object = this.fileLock;
			synchronized (object) {
				try {
					this.plugin.getDataFolder().mkdirs();
					Files.writeString(this.file.toPath(), (CharSequence)data, StandardCharsets.UTF_8, new OpenOption[0]);
				}
				catch (IOException ex) {
					this.plugin.getLogger().log(Level.SEVERE, "Kunne ikke gemme locations.yml: " + ex.getMessage(), ex);
				}
			}
		};
		if (this.plugin.isEnabled()) {
			this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin)this.plugin, task);
		} else {
			task.run();
		}
	}

	public static final class Arena {
		private final String name;
		private volatile Point spawn;
		private volatile Point pos1;
		private volatile Point pos2;
		private final Set<String> kits = ConcurrentHashMap.newKeySet();

		private Arena(String name) {
			this.name = name;
		}

		public String name() {
			return this.name;
		}

		public Point spawn() {
			return this.spawn;
		}

		public List<String> kits() {
			ArrayList<String> out = new ArrayList<String>(this.kits);
			Collections.sort(out);
			return out;
		}

		public boolean allows(String kitId) {
			return this.kits.isEmpty() || kitId != null && this.kits.contains(kitId.toLowerCase(Locale.ROOT));
		}

		public boolean hasRegion() {
			Point a = this.pos1;
			Point b = this.pos2;
			return a != null && b != null && a.world().equals(b.world());
		}
	}
}