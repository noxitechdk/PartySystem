package dk.blockcraft.partysystem.placeholder;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.manager.StatsManager;
import dk.blockcraft.partysystem.model.PartyPlayerData;
import dk.blockcraft.partysystem.model.PartyStats;
import java.util.List;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class PartyExpansion
extends PlaceholderExpansion {
	private final PartySystem plugin;

	public PartyExpansion(PartySystem plugin) {
		this.plugin = plugin;
	}

	@Override
	public String getIdentifier() {
		return "party";
	}

	@Override
	public String getAuthor() {
		return "BlockCraft";
	}

	@Override
	public String getVersion() {
		return this.plugin.getPluginMeta().getVersion();
	}

	@Override
	public boolean persist() {
		return true;
	}

	@Override
	public String onRequest(OfflinePlayer player, String params) {
		String key = params.toLowerCase();
		if (key.equals("count")) {
			return String.valueOf(this.plugin.manager().count());
		}
		if (key.equals("queue_size")) {
			return String.valueOf(this.plugin.queue().size());
		}
		if (key.startsWith("top_")) {
			return this.top(key);
		}
		if (player == null) {
			return "";
		}
		String rankedValue = this.ranked(player, key);
		if (rankedValue != null) {
			return rankedValue;
		}
		switch (key) {
			case "in_queue": {
				return this.plugin.manager().yesNo(this.plugin.queue().isQueued(player.getUniqueId()));
			}
			case "in_group": {
				return this.plugin.manager().yesNo(this.plugin.groups().inGroup(player.getUniqueId()));
			}
			case "group_size": {
				return String.valueOf(this.plugin.groups().sizeOf(player.getUniqueId()));
			}
			case "group_max": {
				return String.valueOf(this.plugin.groups().maxSize());
			}
			case "group_leader": {
				return this.plugin.groups().leaderNameOf(player.getUniqueId());
			}
			case "group_members": {
				return this.plugin.groups().memberNamesOf(player.getUniqueId());
			}
		}
		if (key.equals("restock_cooldown") || key.equals("restock_ready")) {
			long seconds;
			Player online = player.getPlayer();
			long l = seconds = online == null ? 0L : this.plugin.manager().restockCooldownSeconds(online);
			return key.equals("restock_ready") ? this.plugin.manager().yesNo(seconds <= 0L) : String.valueOf(seconds);
		}
		PartyPlayerData data = this.plugin.dataManager().get(player.getUniqueId());
		switch (key) {
			case "in_party": {
				return this.plugin.manager().yesNo(this.plugin.manager().inParty(player.getUniqueId()));
			}
			case "in_builder": {
				return this.plugin.manager().yesNo(this.plugin.manager().inBuilder(player.getUniqueId()));
			}
			case "has_kit": {
				return data == null ? "" : this.plugin.manager().yesNo(data.hasKit());
			}
			case "restocks": {
				return data == null ? "" : String.valueOf(data.getRestocks());
			}
			case "joins": {
				return data == null ? "" : String.valueOf(data.getJoins());
			}
			case "last_save": {
				return data == null ? "" : this.plugin.manager().formatTime(data.getLastSave());
			}
		}
		return null;
	}

	private String top(String key) {
		int position;
		String[] parts = key.split("_");
		if (parts.length != 4) {
			return null;
		}
		StatsManager.Top type = StatsManager.Top.parse(parts[1]);
		if (type == null) {
			return null;
		}
		try {
			position = Integer.parseInt(parts[2]);
		}
		catch (NumberFormatException ex) {
			return null;
		}
		boolean name = parts[3].equals("name");
		if (!name && !parts[3].equals("value")) {
			return null;
		}
		List<PartyStats> list = this.plugin.stats().top(type);
		if (position < 1 || position > list.size()) {
			return name ? "-" : "0";
		}
		PartyStats s = list.get(position - 1);
		return name ? s.name() : String.valueOf(type.value(s));
	}

	private String ranked(OfflinePlayer player, String key) {
		switch (key) {
			case "elo":
			case "peak_elo":
			case "wins":
			case "losses":
			case "draws":
			case "matches":
			case "winrate":
			case "streak":
			case "best_streak":
			case "kills":
			case "deaths":
			case "kdr": {
				break;
			}
			default: {
				return null;
			}
		}
		PartyStats s = this.plugin.stats().statsOrFresh(player.getUniqueId(), player.getName());
		return switch (key) {
			case "elo" -> String.valueOf(s.elo());
			case "peak_elo" -> String.valueOf(s.peakElo());
			case "wins" -> String.valueOf(s.wins());
			case "losses" -> String.valueOf(s.losses());
			case "draws" -> String.valueOf(s.draws());
			case "matches" -> String.valueOf(s.matches());
			case "winrate" -> String.valueOf(s.winRate());
			case "streak" -> String.valueOf(s.streak());
			case "best_streak" -> String.valueOf(s.bestStreak());
			case "kills" -> String.valueOf(s.kills());
			case "deaths" -> String.valueOf(s.deaths());
			default -> String.format(Locale.ROOT, "%.2f", s.kdr());
		};
	}
}