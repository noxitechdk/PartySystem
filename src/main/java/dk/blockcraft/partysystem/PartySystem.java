package dk.blockcraft.partysystem;

import dk.blockcraft.partysystem.command.PartyCommand;
import dk.blockcraft.partysystem.data.DataManager;
import dk.blockcraft.partysystem.data.Database;
import dk.blockcraft.partysystem.data.LocationStore;
import dk.blockcraft.partysystem.listener.PartyListener;
import dk.blockcraft.partysystem.manager.GroupManager;
import dk.blockcraft.partysystem.manager.HologramManager;
import dk.blockcraft.partysystem.manager.KitEditor;
import dk.blockcraft.partysystem.manager.KitManager;
import dk.blockcraft.partysystem.manager.MatchMenu;
import dk.blockcraft.partysystem.manager.PartyManager;
import dk.blockcraft.partysystem.manager.QueueManager;
import dk.blockcraft.partysystem.manager.StatsManager;
import dk.blockcraft.partysystem.placeholder.PartyExpansion;
import dk.blockcraft.partysystem.util.Messages;
import dk.blockcraft.partysystem.util.Settings;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PartySystem
extends JavaPlugin {
	private Settings settings;
	private Messages messages;
	private LocationStore store;
	private Database database;
	private DataManager dataManager;
	private KitManager kits;
	private PartyListener listener;
	private KitEditor editor;
	private PartyManager manager;
	private GroupManager groups;
	private MatchMenu menu;
	private StatsManager stats;
	private QueueManager queue;
	private HologramManager holograms;

	@Override
	public void onEnable() {
		try {
			this.settings = new Settings(this);
			this.messages = new Messages(this.settings);
			this.store = new LocationStore(this);
			this.database = new Database(this);
			this.dataManager = new DataManager(this, this.database);
			this.stats = new StatsManager(this);
			this.kits = new KitManager(this);
			this.kits.load();
			this.editor = new KitEditor(this);
			this.manager = new PartyManager(this);
			this.groups = new GroupManager(this);
			this.queue = new QueueManager(this);
			this.holograms = new HologramManager(this);
			this.getServer().getPluginManager().registerEvents(this.holograms, this);
			this.menu = new MatchMenu(this);
			this.getServer().getPluginManager().registerEvents(this.menu, this);
			this.listener = new PartyListener(this);
			this.getServer().getPluginManager().registerEvents(this.listener, this);
			PartyCommand command = new PartyCommand(this);
			PluginCommand party = this.getCommand("party");
			party.setExecutor(command);
			party.setTabCompleter(command);
			if (this.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
				new PartyExpansion(this).register();
				this.getLogger().info("PlaceholderAPI fundet, placeholders er registreret.");
			}
			this.manager.start();
			this.stats.start();
			this.queue.start();
			this.holograms.start();
			this.getLogger().info("PartySystem er aktiveret.");
		}
		catch (Exception ex) {
			this.getLogger().log(Level.SEVERE, "PartySystem kunne ikke starte: " + ex.getMessage(), ex);
			this.getServer().getPluginManager().disablePlugin(this);
		}
	}

	@Override
	public void onDisable() {
		if (this.listener != null) {
			this.listener.clearAllPlaced();
		}
		if (this.editor != null) {
			this.editor.shutdown();
		}
		if (this.groups != null) {
			this.groups.shutdown();
		}
		if (this.holograms != null) {
			this.holograms.shutdown();
		}
		if (this.queue != null) {
			this.queue.shutdown();
		}
		if (this.stats != null) {
			this.stats.shutdown();
		}
		if (this.manager != null) {
			this.manager.shutdown();
		}
		if (this.dataManager != null) {
			this.dataManager.shutdown();
		}
	}

	public Settings settings() {
		return this.settings;
	}

	public Messages messages() {
		return this.messages;
	}

	public PartyListener listener() {
		return this.listener;
	}

	public LocationStore store() {
		return this.store;
	}

	public DataManager dataManager() {
		return this.dataManager;
	}

	public KitManager kits() {
		return this.kits;
	}

	public KitEditor editor() {
		return this.editor;
	}

	public PartyManager manager() {
		return this.manager;
	}

	public MatchMenu menu() {
		return this.menu;
	}

	public StatsManager stats() {
		return this.stats;
	}

	public QueueManager queue() {
		return this.queue;
	}

	public HologramManager holograms() {
		return this.holograms;
	}

	public GroupManager groups() {
		return this.groups;
	}
}