package dk.blockcraft.partysystem.command;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.data.LocationStore;
import dk.blockcraft.partysystem.manager.HologramManager;
import dk.blockcraft.partysystem.manager.KitManager;
import dk.blockcraft.partysystem.manager.StatsManager;
import dk.blockcraft.partysystem.model.PartyGroup;
import dk.blockcraft.partysystem.model.PartyPlayerData;
import dk.blockcraft.partysystem.model.PartyStats;
import dk.blockcraft.partysystem.util.Messages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class PartyCommand
implements CommandExecutor,
TabCompleter {
	private static final List<String> PLAYER_SUBS = List.of("kit", "start", "save", "edit", "restock", "leave", "status", "create", "menu", "invite", "accept", "deny", "disband", "remove", "list", "help", "queue", "stats", "top");
	private static final List<String> ADMIN_SUBS = List.of("editkit", "savekit", "cancelkit", "setspawn", "setpos1", "setpos2", "delspawn", "arenas", "arenakit", "open", "close", "reload", "resetkit", "resetcooldown", "kick", "info", "hologram", "setelo", "resetstats");
	private final PartySystem plugin;

	public PartyCommand(PartySystem plugin) {
		this.plugin = plugin;
	}

	private Messages msg() {
		return this.plugin.messages();
	}

	private String yesNoText(boolean value) {
		return this.plugin.manager().yesNo(value);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
			if (!sender.hasPermission("partysystem.use")) {
				this.msg().send(sender, "no-permission", new TagResolver[0]);
				return true;
			}
			this.sendHelp(sender);
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (PLAYER_SUBS.contains(sub)) {
			if (!sender.hasPermission("partysystem.use")) {
				this.msg().send(sender, "no-permission", new TagResolver[0]);
				return true;
			}
			if (!(sender instanceof Player)) {
				this.msg().send(sender, "players-only", new TagResolver[0]);
				return true;
			}
			Player player = (Player)sender;
			switch (sub) {
				case "kit": {
					this.kit(player, args);
					break;
				}
				case "start": {
					this.plugin.manager().startMatch(player, args.length < 2 ? null : args[1]);
					break;
				}
				case "menu": {
					this.plugin.menu().openFromCommand(player);
					break;
				}
				case "save": {
					this.plugin.manager().save(player);
					break;
				}
				case "edit": {
					this.plugin.manager().edit(player);
					break;
				}
				case "restock": {
					this.plugin.manager().restock(player);
					break;
				}
				case "leave": {
					this.leave(player);
					break;
				}
				case "create": {
					this.plugin.groups().create(player);
					break;
				}
				case "invite": {
					if (args.length < 2) {
						this.msg().send(sender, "usage", Messages.tag("usage", "/party invite <spiller>"));
						break;
					}
					this.plugin.groups().invite(player, args[1]);
					break;
				}
				case "accept": {
					this.plugin.groups().accept(player);
					break;
				}
				case "deny": {
					this.plugin.groups().deny(player);
					break;
				}
				case "disband": {
					this.plugin.groups().disband(player);
					break;
				}
				case "remove": {
					if (args.length < 2) {
						this.msg().send(sender, "usage", Messages.tag("usage", "/party remove <spiller>"));
						break;
					}
					this.plugin.groups().remove(player, args[1]);
					break;
				}
				case "list": {
					this.plugin.groups().list(player);
					break;
				}
				case "status": {
					this.plugin.manager().status(player);
					break;
				}
				case "queue": {
					this.queue(player, args);
					break;
				}
				case "stats": {
					this.stats(player, args);
					break;
				}
				case "top": {
					this.top(player, args);
					break;
				}
				default: {
					this.sendHelp(sender);
				}
			}
			return true;
		}
		if (ADMIN_SUBS.contains(sub)) {
			if (!sender.hasPermission("partysystem.admin")) {
				this.msg().send(sender, "no-permission", new TagResolver[0]);
				return true;
			}
			this.admin(sender, sub, args);
			return true;
		}
		this.msg().send(sender, "unknown-command", new TagResolver[0]);
		return true;
	}

	private void kit(Player player, String[] args) {
		if (args.length >= 2) {
			this.plugin.manager().chooseKit(player, args[1]);
			return;
		}
		List<KitManager.Kit> kits = this.plugin.kits().all();
		if (kits.isEmpty()) {
			this.msg().send(player, "kit-none", new TagResolver[0]);
			return;
		}
		this.msg().send(player, "kit-list-header", new TagResolver[0]);
		for (KitManager.Kit kit : kits) {
			this.msg().send(player, "kit-list-entry", Messages.tag("id", kit.id()), Messages.mini("kit", kit.displayName()));
		}
	}

	private void leave(Player player) {
		UUID uuid = player.getUniqueId();
		if (this.plugin.queue().isQueued(uuid)) {
			this.plugin.queue().leave(player, true);
			return;
		}
		PartyGroup group = this.plugin.groups().groupOf(uuid);
		if (group != null && group.isLeader(uuid) && !this.plugin.manager().inMatch(uuid)) {
			return;
		}
		if (this.plugin.manager().inParty(uuid) || group == null) {
			this.plugin.manager().leave(player);
		} else {
			this.plugin.groups().leave(player);
		}
	}

	private void admin(CommandSender sender, String sub, String[] args) {
		block21 : switch (sub) {
			case "setspawn":
			case "setpos1":
			case "setpos2": {
				String arena;
				if (!(sender instanceof Player)) {
					this.msg().send(sender, "players-only", new TagResolver[0]);
					return;
				}
				Player p = (Player)sender;
				String string = arena = args.length >= 2 ? LocationStore.normalize(args[1]) : "default";
				if (!LocationStore.validName(arena)) {
					this.msg().send(sender, "arena-bad-name", new TagResolver[0]);
					return;
				}
				switch (sub) {
					case "setspawn": {
						this.plugin.store().setSpawn(arena, p.getLocation());
						this.msg().send(sender, "spawn-set", Messages.tag("arena", arena));
						break block21;
					}
					case "setpos1": {
						this.plugin.store().setPos1(arena, p.getLocation());
						this.msg().send(sender, "pos1-set", Messages.tag("arena", arena));
						break block21;
					}
				}
				this.plugin.store().setPos2(arena, p.getLocation());
				this.msg().send(sender, "pos2-set", Messages.tag("arena", arena));
				break;
			}
			case "delspawn": {
				if (args.length < 2) {
					this.msg().send(sender, "usage", Messages.tag("usage", "/party delspawn <arena>"));
					break;
				}
				if (this.plugin.store().removeArena(args[1])) {
					this.msg().send(sender, "arena-deleted", Messages.tag("arena", LocationStore.normalize(args[1])));
					break;
				}
				this.msg().send(sender, "arena-not-found", Messages.tag("arena", args[1]));
				break;
			}
			case "arenakit": {
				boolean needKit;
				String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
				boolean bl = needKit = action.equals("add") || action.equals("remove");
				if (args.length < 3 || !needKit && !action.equals("clear") || needKit && args.length < 4) {
					this.msg().send(sender, "usage", Messages.tag("usage", "/party arenakit <arena> <add|remove|clear> [kit]"));
					break;
				}
				if (needKit && this.plugin.kits().get(args[3]) == null) {
					this.msg().send(sender, "kit-not-found", Messages.tag("kit", args[3]));
					break;
				}
				if (!this.plugin.store().editKits(args[1], action, needKit ? args[3] : "")) {
					this.msg().send(sender, "arena-not-found", Messages.tag("arena", args[1]));
					break;
				}
				List<String> now = this.plugin.store().arena(args[1]).kits();
				this.msg().send(sender, "arenakit-updated", Messages.tag("arena", LocationStore.normalize(args[1])), Messages.tag("kits", now.isEmpty() ? "alle gamemodes" : String.join((CharSequence)", ", now)));
				break;
			}
			case "arenas": {
				List<String> names = this.plugin.store().arenaNames();
				if (names.isEmpty()) {
					this.msg().send(sender, "arena-none", new TagResolver[0]);
					return;
				}
				this.msg().send(sender, "arena-list-header", new TagResolver[0]);
				for (String name : names) {
					LocationStore.Arena a = this.plugin.store().arena(name);
					this.msg().send(sender, "arena-list-entry", Messages.tag("arena", name), Messages.tag("spawn", this.yesNoText(a.spawn() != null)), Messages.tag("region", this.yesNoText(a.hasRegion())), Messages.tag("players", this.plugin.manager().playersInArena(name)), Messages.tag("kits", a.kits().isEmpty() ? "alle" : String.join((CharSequence)", ", a.kits())), Messages.tag("busy", this.yesNoText(this.plugin.manager().arenaBusy(name))));
				}
				break;
			}
			case "open": {
				this.plugin.store().setOpen(true);
				this.msg().send(sender, "opened", new TagResolver[0]);
				break;
			}
			case "close": {
				this.plugin.store().setOpen(false);
				if (this.plugin.settings().config().getBoolean("close.kick-players", true)) {
					this.plugin.manager().kickAll("party-closed");
				}
				this.msg().send(sender, "closed-done", new TagResolver[0]);
				break;
			}
			case "reload": {
				this.plugin.settings().reloadAsync(ok -> {
					if (ok.booleanValue()) {
						this.plugin.kits().load();
						this.plugin.groups().refreshAll();
						this.plugin.stats().restartTimer();
						this.plugin.holograms().reload();
					}
					this.msg().send(sender, ok != false ? "reloaded" : "reload-failed", new TagResolver[0]);
				});
				break;
			}
			case "editkit":
			case "savekit":
			case "cancelkit": {
				if (!(sender instanceof Player)) {
					this.msg().send(sender, "players-only", new TagResolver[0]);
					break;
				}
				Player p = (Player)sender;
				if (sub.equals("editkit")) {
					if (args.length < 2) {
						this.msg().send(sender, "usage", Messages.tag("usage", "/party editkit <kit>"));
						break;
					}
					this.plugin.editor().start(p, args[1]);
					break;
				}
				if (sub.equals("savekit")) {
					this.plugin.editor().save(p);
					break;
				}
				this.plugin.editor().cancel(p);
				break;
			}
			case "resetkit": {
				this.resetKit(sender, args);
				break;
			}
			case "resetcooldown": {
				this.resetCooldown(sender, args);
				break;
			}
			case "kick": {
				this.kick(sender, args);
				break;
			}
			case "info": {
				this.info(sender, args);
				break;
			}
			case "hologram": {
				this.hologram(sender, args);
				break;
			}
			case "setelo": {
				this.setElo(sender, args);
				break;
			}
			case "resetstats": {
				this.resetStats(sender, args);
				break;
			}
			default: {
				this.msg().send(sender, "unknown-command", new TagResolver[0]);
			}
		}
	}

	private void resetKit(CommandSender sender, String[] args) {
		if (args.length < 2) {
			this.msg().send(sender, "usage", Messages.tag("usage", "/party resetkit <spiller>"));
			return;
		}
		OfflinePlayer target = this.find(args[1]);
		if (target == null) {
			this.msg().send(sender, "player-not-found", Messages.tag("player", args[1]));
			return;
		}
		String name = this.name(target, args[1]);
		this.plugin.dataManager().update(target.getUniqueId(), data -> {
			data.setKitItems(null);
			data.setLastSave(0L);
		}).whenComplete((v, error) -> this.sync(() -> {
			if (error != null) {
				this.plugin.getLogger().log(Level.SEVERE, "resetkit fejlede: " + error.getMessage(), (Throwable)error);
				this.msg().send(sender, "error", new TagResolver[0]);
			} else {
				this.msg().send(sender, "kit-reset", Messages.tag("player", name));
			}
		}));
	}

	private void resetCooldown(CommandSender sender, String[] args) {
		if (args.length < 2) {
			this.msg().send(sender, "usage", Messages.tag("usage", "/party resetcooldown <spiller>"));
			return;
		}
		OfflinePlayer target = this.find(args[1]);
		if (target == null) {
			this.msg().send(sender, "player-not-found", Messages.tag("player", args[1]));
			return;
		}
		this.plugin.manager().resetCooldown(target.getUniqueId());
		this.msg().send(sender, "cooldown-reset", Messages.tag("player", this.name(target, args[1])));
	}

	private void kick(CommandSender sender, String[] args) {
		if (args.length < 2) {
			this.msg().send(sender, "usage", Messages.tag("usage", "/party kick <spiller>"));
			return;
		}
		Player target = Bukkit.getPlayerExact(args[1]);
		if (target == null) {
			this.msg().send(sender, "player-not-found", Messages.tag("player", args[1]));
			return;
		}
		if (this.plugin.manager().removeWithMessage(target, "kicked")) {
			this.msg().send(sender, "kick-success", Messages.tag("player", target.getName()));
		} else {
			this.msg().send(sender, "kick-not-in-party", Messages.tag("player", target.getName()));
		}
	}

	private void info(CommandSender sender, String[] args) {
		if (args.length < 2) {
			this.msg().send(sender, "usage", Messages.tag("usage", "/party info <spiller>"));
			return;
		}
		OfflinePlayer target = this.find(args[1]);
		if (target == null) {
			this.msg().send(sender, "player-not-found", Messages.tag("player", args[1]));
			return;
		}
		UUID uuid = target.getUniqueId();
		String name = this.name(target, args[1]);
		this.plugin.dataManager().fetch(uuid).whenComplete((data, error) -> this.sync(() -> {
			if (error != null || data == null) {
				if (error != null) {
					this.plugin.getLogger().log(Level.SEVERE, "info fejlede: " + error.getMessage(), (Throwable)error);
				}
				this.msg().send(sender, "error", new TagResolver[0]);
				return;
			}
			this.sendInfo(sender, name, uuid, (PartyPlayerData)data);
		}));
	}

	private void sendInfo(CommandSender sender, String name, UUID uuid, PartyPlayerData data) {
		boolean in = this.plugin.manager().inParty(uuid) || data.getOriginal() != null;
		this.msg().sendList(sender, "info", Messages.tag("player", name), Messages.tag("in_party", this.plugin.manager().yesNo(in)), Messages.tag("has_kit", this.plugin.manager().yesNo(data.hasKit())), Messages.tag("last_save", this.plugin.manager().formatTime(data.getLastSave())), Messages.tag("restocks", data.getRestocks()), Messages.tag("joins", data.getJoins()));
	}

	private void queue(Player player, String[] args) {
		if (args.length < 2) {
			if (this.plugin.queue().isQueued(player.getUniqueId())) {
				this.plugin.queue().status(player);
				return;
			}
			this.msg().send(player, "queue-usage", new TagResolver[0]);
			this.msg().send(player, "queue-kits", Messages.tag("kits", String.join((CharSequence)", ", this.plugin.kits().ids())));
			return;
		}
		String arg = args[1].toLowerCase(Locale.ROOT);
		if (arg.equals("leave") || arg.equals("cancel")) {
			if (!this.plugin.queue().leave(player, true)) {
				this.msg().send(player, "queue-not-in", new TagResolver[0]);
			}
			return;
		}
		this.plugin.queue().join(player, args[1]);
	}

	private void stats(Player player, String[] args) {
		if (args.length < 2) {
			this.showStats(player, this.plugin.stats().fetch(player.getUniqueId(), player.getName()), player.getName());
		} else {
			this.showStats(player, this.plugin.stats().findByName(args[1]), args[1]);
		}
	}

	private void showStats(CommandSender sender, CompletableFuture<PartyStats> lookup, String fallbackName) {
		lookup.<StatsManager.View>thenCompose(stats -> stats == null ? CompletableFuture.completedFuture(null) : this.plugin.stats().rank(stats).thenApply(rank -> new StatsManager.View(stats, rank))).whenComplete((view, error) -> this.sync(() -> {
			if (error != null) {
				this.plugin.getLogger().log(Level.SEVERE, "stats fejlede: " + error.getMessage(), (Throwable)error);
				this.msg().send(sender, "error", new TagResolver[0]);
			} else if (view == null) {
				this.msg().send(sender, "player-not-found", Messages.tag("player", fallbackName));
			} else {
				this.sendStats(sender, (StatsManager.View)view);
			}
		}));
	}

	private void sendStats(CommandSender sender, StatsManager.View view) {
		TagResolver[] base = StatsManager.tags(view.stats());
		TagResolver[] all = Arrays.copyOf(base, base.length + 1);
		String rank = view.rank() < 0 ? this.plugin.settings().messages().getString("ranked-unranked", "Ikke placeret") : "#" + view.rank();
		all[base.length] = Messages.tag("rank", rank);
		this.msg().sendList(sender, "ranked-stats", all);
	}

	private void top(Player player, String[] args) {
		StatsManager.Top type;
		StatsManager.Top top = type = args.length >= 2 ? StatsManager.Top.parse(args[1]) : StatsManager.Top.ELO;
		if (type == null) {
			this.msg().send(player, "usage", Messages.tag("usage", "/party top <" + StatsManager.Top.names().replace(", ", "|") + ">"));
			return;
		}
		List<PartyStats> list = this.plugin.stats().top(type);
		this.msg().send(player, "ranked-top-header", Messages.tag("type", type.id()));
		if (list.isEmpty()) {
			this.msg().send(player, "ranked-top-empty", new TagResolver[0]);
			return;
		}
		for (int i = 0; i < Math.min(10, list.size()); ++i) {
			PartyStats s = list.get(i);
			ArrayList<TagResolver> tags = new ArrayList<TagResolver>(List.of(StatsManager.tags(s)));
			tags.add(Messages.tag("position", i + 1));
			tags.add(Messages.tag("value", type.value(s)));
			this.msg().send(player, "ranked-top-entry", tags.toArray(new TagResolver[0]));
		}
	}

	private void hologram(CommandSender sender, String[] args) {
		String action;
		HologramManager holos = this.plugin.holograms();
		switch (action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "") {
			case "create": {
				StatsManager.Top type;
				if (!(sender instanceof Player)) {
					this.msg().send(sender, "players-only", new TagResolver[0]);
					return;
				}
				Player p = (Player)sender;
				StatsManager.Top top = type = args.length >= 3 ? StatsManager.Top.parse(args[2]) : null;
				if (type == null) {
					this.msg().send(sender, "usage", Messages.tag("usage", "/party hologram create <" + StatsManager.Top.names().replace(", ", "|") + ">"));
					return;
				}
				String id = holos.create(p.getLocation(), type);
				this.msg().send(sender, "holo-created", Messages.tag("id", id), Messages.tag("type", type.id()));
				break;
			}
			case "remove": {
				if (args.length < 3) {
					this.msg().send(sender, "usage", Messages.tag("usage", "/party hologram remove <id>"));
					break;
				}
				if (holos.remove(args[2])) {
					this.msg().send(sender, "holo-removed", Messages.tag("id", args[2]));
					break;
				}
				this.msg().send(sender, "holo-not-found", Messages.tag("id", args[2]));
				break;
			}
			case "move": {
				if (!(sender instanceof Player)) {
					this.msg().send(sender, "players-only", new TagResolver[0]);
					break;
				}
				Player p = (Player)sender;
				if (args.length < 3) {
					this.msg().send(sender, "usage", Messages.tag("usage", "/party hologram move <id>"));
					break;
				}
				if (holos.move(args[2], p.getLocation())) {
					this.msg().send(sender, "holo-moved", Messages.tag("id", args[2]));
					break;
				}
				this.msg().send(sender, "holo-not-found", Messages.tag("id", args[2]));
				break;
			}
			case "list": {
				if (holos.all().isEmpty()) {
					this.msg().send(sender, "holo-none", new TagResolver[0]);
					return;
				}
				this.msg().send(sender, "holo-list-header", new TagResolver[0]);
				for (HologramManager.Holo h : holos.all()) {
					this.msg().send(sender, "holo-list-entry", Messages.tag("id", h.id()), Messages.tag("type", h.type().id()), Messages.tag("world", h.world()), Messages.tag("x", Math.round(h.x())), Messages.tag("y", Math.round(h.y())), Messages.tag("z", Math.round(h.z())));
				}
				break;
			}
			case "refresh": {
				this.plugin.stats().refreshTops();
				this.msg().send(sender, "holo-refreshed", new TagResolver[0]);
				break;
			}
			default: {
				this.msg().send(sender, "usage", Messages.tag("usage", "/party hologram <create|remove|move|list|refresh>"));
			}
		}
	}

	private void setElo(CommandSender sender, String[] args) {
		int elo;
		if (args.length < 3) {
			this.msg().send(sender, "usage", Messages.tag("usage", "/party setelo <spiller> <elo>"));
			return;
		}
		try {
			elo = Integer.parseInt(args[2]);
		}
		catch (NumberFormatException ex) {
			this.msg().send(sender, "usage", Messages.tag("usage", "/party setelo <spiller> <elo>"));
			return;
		}
		this.plugin.stats().findByName(args[1]).<Boolean>thenCompose(found -> {
			if (found == null) {
				return CompletableFuture.completedFuture(false);
			}
			return this.plugin.stats().setElo(found.uuid(), found.name(), elo).thenApply(x -> true);
		}).whenComplete((done, error) -> this.sync(() -> {
			if (error != null) {
				this.plugin.getLogger().log(Level.SEVERE, "setelo fejlede: " + error.getMessage(), (Throwable)error);
				this.msg().send(sender, "error", new TagResolver[0]);
			} else if (!done.booleanValue()) {
				this.msg().send(sender, "player-not-found", Messages.tag("player", args[1]));
			} else {
				this.msg().send(sender, "setelo-done", Messages.tag("player", args[1]), Messages.tag("elo", Math.max(0, elo)));
			}
		}));
	}

	private void resetStats(CommandSender sender, String[] args) {
		if (args.length < 2) {
			this.msg().send(sender, "usage", Messages.tag("usage", "/party resetstats <spiller>"));
			return;
		}
		this.plugin.stats().findByName(args[1]).<Boolean>thenCompose(found -> {
			if (found == null) {
				return CompletableFuture.completedFuture(false);
			}
			return this.plugin.stats().reset(found.uuid(), found.name()).thenApply(x -> true);
		}).whenComplete((done, error) -> this.sync(() -> {
			if (error != null) {
				this.plugin.getLogger().log(Level.SEVERE, "resetstats fejlede: " + error.getMessage(), (Throwable)error);
				this.msg().send(sender, "error", new TagResolver[0]);
			} else if (!done.booleanValue()) {
				this.msg().send(sender, "player-not-found", Messages.tag("player", args[1]));
			} else {
				this.msg().send(sender, "stats-reset", Messages.tag("player", args[1]));
			}
		}));
	}

	private void sendHelp(CommandSender sender) {
		this.msg().send(sender, "help.header", new TagResolver[0]);
		this.msg().sendList(sender, "help.player", new TagResolver[0]);
		this.msg().sendList(sender, "help-ranked", new TagResolver[0]);
		if (sender.hasPermission("partysystem.admin")) {
			this.msg().sendList(sender, "help.admin", new TagResolver[0]);
			this.msg().sendList(sender, "help-ranked-admin", new TagResolver[0]);
		}
	}

	private OfflinePlayer find(String name) {
		Player online = Bukkit.getPlayerExact(name);
		if (online != null) {
			return online;
		}
		return Bukkit.getOfflinePlayerIfCached(name);
	}

	private String name(OfflinePlayer player, String fallback) {
		return player.getName() != null ? player.getName() : fallback;
	}

	private void sync(Runnable task) {
		if (!this.plugin.isEnabled()) {
			return;
		}
		if (Bukkit.isPrimaryThread()) {
			task.run();
		} else {
			Bukkit.getScheduler().runTask((Plugin)this.plugin, task);
		}
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		ArrayList<String> out = new ArrayList<String>();
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			if (sender.hasPermission("partysystem.use")) {
				PLAYER_SUBS.stream().filter(s -> s.startsWith(prefix)).forEach(out::add);
			}
			if (sender.hasPermission("partysystem.admin")) {
				ADMIN_SUBS.stream().filter(s -> s.startsWith(prefix)).forEach(out::add);
			}
		} else if (args.length == 2 && sender.hasPermission("partysystem.admin")) {
			String sub = args[0].toLowerCase(Locale.ROOT);
			String prefix = args[1].toLowerCase(Locale.ROOT);
			if (sub.equals("setspawn") || sub.equals("setpos1") || sub.equals("setpos2") || sub.equals("delspawn") || sub.equals("arenakit")) {
				this.plugin.store().arenaNames().stream().filter(id -> id.startsWith(prefix)).forEach(out::add);
			} else if (sub.equals("editkit")) {
				this.plugin.kits().ids().stream().filter(id -> id.startsWith(prefix)).forEach(out::add);
			} else if (sub.equals("resetkit") || sub.equals("resetcooldown") || sub.equals("kick") || sub.equals("info") || sub.equals("setelo") || sub.equals("resetstats")) {
				for (Player player : Bukkit.getOnlinePlayers()) {
					if (!player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
					out.add(player.getName());
				}
			}
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("queue") && sender.hasPermission("partysystem.use")) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			if ("leave".startsWith(prefix)) {
				out.add("leave");
			}
			this.plugin.kits().ids().stream().filter(id -> id.startsWith(prefix)).forEach(out::add);
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("stats") && sender.hasPermission("partysystem.use")) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (!player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
				out.add(player.getName());
			}
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("top") && sender.hasPermission("partysystem.use")) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			for (StatsManager.Top t : StatsManager.Top.values()) {
				if (t.id().startsWith(prefix)) {
					out.add(t.id());
				}
			}
		}
		if (sender.hasPermission("partysystem.admin") && args.length >= 2 && args[0].equalsIgnoreCase("hologram")) {
			String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
			if (args.length == 2) {
				List.of("create", "remove", "move", "list", "refresh").stream().filter(x -> x.startsWith(prefix)).forEach(out::add);
			} else if (args.length == 3 && args[1].equalsIgnoreCase("create")) {
				for (StatsManager.Top t : StatsManager.Top.values()) {
					if (t.id().startsWith(prefix)) {
						out.add(t.id());
					}
				}
			} else if (args.length == 3 && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("move"))) {
				this.plugin.holograms().ids().stream().filter(id -> id.startsWith(prefix)).forEach(out::add);
			}
		}
		if (args.length == 2 && (args[0].equalsIgnoreCase("kit") || args[0].equalsIgnoreCase("start")) && sender.hasPermission("partysystem.use")) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			this.plugin.kits().ids().stream().filter(id -> id.startsWith(prefix)).forEach(out::add);
		}
		if (args.length == 2 && sender instanceof Player) {
			Player self = (Player)sender;
			if (sender.hasPermission("partysystem.use")) {
				this.groupTab(self, args[0].toLowerCase(Locale.ROOT), args[1].toLowerCase(Locale.ROOT), out);
			}
		}
		return out;
	}

	private void groupTab(Player self, String sub, String prefix, List<String> out) {
		block4: {
			block3: {
				if (!sub.equals("invite")) break block3;
				for (Player player : Bukkit.getOnlinePlayers()) {
					if (player.getUniqueId().equals(self.getUniqueId()) || this.plugin.groups().inGroup(player.getUniqueId()) || !player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
					out.add(player.getName());
				}
				break block4;
			}
			if (!sub.equals("remove")) break block4;
			PartyGroup group = this.plugin.groups().groupOf(self.getUniqueId());
			if (group == null) {
				return;
			}
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (!group.contains(player.getUniqueId()) || player.getUniqueId().equals(self.getUniqueId()) || !player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
				out.add(player.getName());
			}
		}
	}
}