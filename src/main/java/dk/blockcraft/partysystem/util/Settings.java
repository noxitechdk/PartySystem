package dk.blockcraft.partysystem.util;

import dk.blockcraft.partysystem.PartySystem;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

public final class Settings {
	private final PartySystem plugin;
	private volatile YamlConfiguration config;
	private volatile YamlConfiguration messages;

	public Settings(PartySystem plugin) {
		this.plugin = plugin;
		plugin.saveDefaultConfig();
		if (!new File(plugin.getDataFolder(), "messages.yml").exists()) {
			plugin.saveResource("messages.yml", false);
		}
		this.mergeMissing("config.yml");
		this.mergeMissing("messages.yml");
		this.config = this.read("config.yml");
		this.messages = this.read("messages.yml");
	}

	public YamlConfiguration config() {
		return this.config;
	}

	public YamlConfiguration messages() {
		return this.messages;
	}

	public void reloadAsync(Consumer<Boolean> done) {
		this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin)this.plugin, () -> {
			try {
				YamlConfiguration newConfig = this.read("config.yml");
				YamlConfiguration newMessages = this.read("messages.yml");
				this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
					this.config = newConfig;
					this.messages = newMessages;
					done.accept(true);
				});
			}
			catch (Exception ex) {
				this.plugin.getLogger().log(Level.SEVERE, "Kunne ikke genindl\u00e6se konfiguration: " + ex.getMessage(), ex);
				this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> done.accept(false));
			}
		});
	}

	private void mergeMissing(String name) {
		File file = new File(this.plugin.getDataFolder(), name);
		try (InputStream in = this.plugin.getResource(name);){
			if (in == null || !file.exists()) {
				return;
			}
			String resource = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
			StringBuilder add = new StringBuilder();
			StringBuilder names = new StringBuilder();
			for (Map.Entry<String, String> block : Settings.topLevelBlocks(resource).entrySet()) {
				if (disk.contains(block.getKey())) continue;
				add.append('\n').append(block.getValue());
				names.append(names.length() == 0 ? "" : ", ").append(block.getKey());
			}
			if (add.length() == 0) {
				return;
			}
			Object existing = Files.readString(file.toPath(), StandardCharsets.UTF_8);
			if (!((String)existing).endsWith("\n")) {
				existing = (String)existing + "\n";
			}
			Files.writeString(file.toPath(), (CharSequence)((String)existing + String.valueOf(add)), StandardCharsets.UTF_8, new OpenOption[0]);
			this.plugin.getLogger().info("Tilf\u00f8jede nye indstillinger til " + name + ": " + String.valueOf(names));
		}
		catch (IOException ex) {
			this.plugin.getLogger().log(Level.WARNING, "Kunne ikke opdatere " + name + " med nye indstillinger: " + ex.getMessage());
		}
	}

	private static Map<String, String> topLevelBlocks(String text) {
		LinkedHashMap<String, String> blocks = new LinkedHashMap<String, String>();
		String key = null;
		StringBuilder current = null;
		StringBuilder comments = new StringBuilder();
		for (String line : text.split("\r?\n", -1)) {
			boolean topKey;
			boolean bl = topKey = !line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && line.charAt(0) != '#' && line.charAt(0) != '-' && line.indexOf(58) > 0;
			if (topKey) {
				if (key != null) {
					blocks.put(key, current.toString());
				}
				key = line.substring(0, line.indexOf(58)).trim().replace("\"", "");
				current = new StringBuilder(comments);
				comments.setLength(0);
				current.append(line).append('\n');
				continue;
			}
			if (line.startsWith("#")) {
				comments.append(line).append('\n');
				continue;
			}
			if (line.isBlank()) {
				if (comments.length() > 0) {
					comments.append('\n');
					continue;
				}
				if (current == null) continue;
				current.append('\n');
				continue;
			}
			if (current == null) continue;
			current.append((CharSequence)comments);
			comments.setLength(0);
			current.append(line).append('\n');
		}
		if (key != null) {
			blocks.put(key, current.toString());
		}
		return blocks;
	}

	private YamlConfiguration read(String name) {
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(this.plugin.getDataFolder(), name));
		try (InputStream in = this.plugin.getResource(name);){
			if (in != null) {
				yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
			}
		}
		catch (IOException ex) {
			this.plugin.getLogger().log(Level.WARNING, "Kunne ikke l\u00e6se standardv\u00e6rdier for " + name + ": " + ex.getMessage());
		}
		return yaml;
	}
}