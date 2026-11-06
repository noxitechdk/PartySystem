package dk.blockcraft.partysystem.manager;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.manager.KitManager;
import dk.blockcraft.partysystem.model.OriginalState;
import dk.blockcraft.partysystem.model.PartyPlayerData;
import dk.blockcraft.partysystem.util.InventoryUtil;
import dk.blockcraft.partysystem.util.Messages;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class KitEditor {
	private final PartySystem plugin;
	private final Map<UUID, Edit> edits = new HashMap<UUID, Edit>();

	public KitEditor(PartySystem plugin) {
		this.plugin = plugin;
	}

	public boolean isEditing(UUID uuid) {
		return this.edits.containsKey(uuid);
	}

	public void start(Player p, String rawId) {
		String id = rawId.toLowerCase(Locale.ROOT);
		if (!KitManager.validId(id)) {
			this.msg(p, "editkit-bad-name", new TagResolver[0]);
			return;
		}
		UUID uuid = p.getUniqueId();
		Edit existing = this.edits.get(uuid);
		if (existing != null) {
			this.msg(p, "editkit-already", Messages.tag("kit", existing.id()));
			return;
		}
		if (this.plugin.manager().inParty(uuid)) {
			this.msg(p, "editkit-in-party", new TagResolver[0]);
			return;
		}
		PartyPlayerData data = this.plugin.dataManager().get(uuid);
		if (data == null) {
			this.msg(p, "data-not-loaded", new TagResolver[0]);
			return;
		}
		OriginalState original = OriginalState.capture(p);
		data.setOriginal(original.toYaml());
		this.plugin.dataManager().save(uuid);
		this.edits.put(uuid, new Edit(id, original));
		KitManager.Kit kit = this.plugin.kits().get(id);
		InventoryUtil.apply(p.getInventory(), kit == null ? new ItemStack[41] : kit.items());
		p.setGameMode(GameMode.CREATIVE);
		this.msg(p, kit == null ? "editkit-started-new" : "editkit-started", Messages.tag("kit", id));
	}

	public void save(Player p) {
		Edit edit = this.edits.get(p.getUniqueId());
		if (edit == null) {
			this.msg(p, "editkit-none", new TagResolver[0]);
			return;
		}
		ItemStack[] items = InventoryUtil.snapshot(p.getInventory());
		boolean empty = true;
		for (ItemStack item : items) {
			if (item == null) continue;
			empty = false;
			break;
		}
		if (empty) {
			this.msg(p, "editkit-empty", new TagResolver[0]);
			return;
		}
		try {
			this.plugin.kits().saveKit(edit.id(), items);
		}
		catch (IOException | RuntimeException ex) {
			this.plugin.getLogger().log(Level.SEVERE, "Kunne ikke gemme kit " + edit.id() + ": " + ex.getMessage(), ex);
			this.msg(p, "editkit-save-failed", new TagResolver[0]);
			return;
		}
		this.plugin.kits().load();
		this.finish(p, edit);
		this.msg(p, "editkit-saved", Messages.tag("kit", edit.id()));
	}

	public void cancel(Player p) {
		Edit edit = this.edits.get(p.getUniqueId());
		if (edit == null) {
			this.msg(p, "editkit-none", new TagResolver[0]);
			return;
		}
		this.finish(p, edit);
		this.msg(p, "editkit-cancelled", Messages.tag("kit", edit.id()));
	}

	public void handleQuit(Player p) {
		Edit edit = this.edits.get(p.getUniqueId());
		if (edit != null) {
			this.finish(p, edit);
		}
	}

	public void shutdown() {
		for (UUID uuid : new ArrayList<UUID>(this.edits.keySet())) {
			Player p = this.plugin.getServer().getPlayer(uuid);
			Edit edit = this.edits.get(uuid);
			if (p == null || edit == null) continue;
			this.finish(p, edit);
		}
		this.edits.clear();
	}

	private void finish(Player p, Edit edit) {
		this.edits.remove(p.getUniqueId());
		edit.original().apply(p);
		p.setGameMode(edit.original().gameMode());
		PartyPlayerData data = this.plugin.dataManager().get(p.getUniqueId());
		if (data != null) {
			data.setOriginal(null);
			this.plugin.dataManager().save(p.getUniqueId());
		}
	}

	private void msg(Player p, String key, TagResolver ... tags) {
		this.plugin.messages().send(p, key, tags);
	}

	private record Edit(String id, OriginalState original) {
	}
}