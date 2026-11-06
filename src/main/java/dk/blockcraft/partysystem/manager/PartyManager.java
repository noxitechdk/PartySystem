package dk.blockcraft.partysystem.manager;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.manager.KitManager;
import dk.blockcraft.partysystem.manager.StatsManager;
import dk.blockcraft.partysystem.model.OriginalState;
import dk.blockcraft.partysystem.model.PartyGroup;
import dk.blockcraft.partysystem.model.PartyPlayerData;
import dk.blockcraft.partysystem.util.InventoryUtil;
import dk.blockcraft.partysystem.util.KitSanitizer;
import dk.blockcraft.partysystem.util.Messages;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class PartyManager {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(ZoneId.systemDefault());
	private final PartySystem plugin;
	private final Map<UUID, Session> sessions = new HashMap<UUID, Session>();
	private final Map<UUID, Match> matches = new HashMap<UUID, Match>();
	private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<UUID, Long>();
	private BukkitTask task;
	private String forcedArena;

	public PartyManager(PartySystem plugin) {
		this.plugin = plugin;
	}

	public void start() {
		this.task = this.plugin.getServer().getScheduler().runTaskTimer((Plugin)this.plugin, this::tick, 20L, 20L);
	}

	public void shutdown() {
		if (this.task != null) {
			this.task.cancel();
			this.task = null;
		}
		for (UUID uuid : new ArrayList<UUID>(this.sessions.keySet())) {
			Player p = Bukkit.getPlayer(uuid);
			if (p == null) continue;
			this.leaveQuietly(p);
		}
		this.sessions.clear();
	}

	public boolean inParty(UUID uuid) {
		return this.sessions.containsKey(uuid);
	}

	public boolean inBuilder(UUID uuid) {
		Session s = this.sessions.get(uuid);
		return s != null && s.builder;
	}

	public int count() {
		return this.sessions.size();
	}

	public String yesNo(boolean value) {
		return this.plugin.settings().messages().getString(value ? "placeholder.yes" : "placeholder.no", value ? "Ja" : "Nej");
	}

	public String formatTime(long millis) {
		if (millis <= 0L) {
			return this.plugin.settings().messages().getString("placeholder.never", "Aldrig");
		}
		return TIME.format(Instant.ofEpochMilli(millis));
	}

	public String formatDuration(long seconds) {
		long s = Math.max(0L, seconds);
		long minutes = s / 60L;
		long rest = s % 60L;
		if (minutes <= 0L) {
			return rest + " sek";
		}
		return rest == 0L ? minutes + " min" : minutes + " min " + rest + " sek";
	}

	private long cooldownMillis() {
		return Math.max(0L, this.plugin.settings().config().getLong("restock.cooldown-seconds", 60L)) * 1000L;
	}

	private long cooldownRemaining(Player p, long now) {
		if (p.hasPermission("partysystem.restock.nocooldown")) {
			return 0L;
		}
		Long until = this.cooldownUntil.get(p.getUniqueId());
		if (until == null) {
			return 0L;
		}
		long remaining = Math.min(until - now, this.cooldownMillis());
		if (remaining <= 0L) {
			this.cooldownUntil.remove(p.getUniqueId(), until);
			return 0L;
		}
		return remaining;
	}

	private void startCooldown(Player p, long now) {
		long millis = this.cooldownMillis();
		if (millis > 0L && !p.hasPermission("partysystem.restock.nocooldown")) {
			this.cooldownUntil.put(p.getUniqueId(), now + millis);
		}
	}

	public long restockCooldownSeconds(Player p) {
		long remaining = this.cooldownRemaining(p, System.currentTimeMillis());
		return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
	}

	public void resetCooldown(UUID uuid) {
		this.cooldownUntil.remove(uuid);
	}

	public void status(Player p) {
		PartyPlayerData data = this.requireData(p);
		if (data == null) {
			return;
		}
		UUID uuid = p.getUniqueId();
		long seconds = this.restockCooldownSeconds(p);
		String cooldown = seconds > 0L ? this.formatDuration(seconds) : this.plugin.settings().messages().getString("placeholder.ready", "Klar");
		this.msg().sendList(p, "status", Messages.tag("in_party", this.yesNo(this.inParty(uuid))), Messages.tag("in_builder", this.yesNo(this.inBuilder(uuid))), Messages.tag("has_kit", this.yesNo(data.hasKit())), Messages.tag("cooldown", cooldown));
	}

	private Session begin(Player p, PartyPlayerData data) {
		Location spawn;
		UUID uuid = p.getUniqueId();
		if (!this.plugin.store().isOpen() && !p.hasPermission("partysystem.admin")) {
			this.msg().send(p, "closed", new TagResolver[0]);
			return null;
		}
		String arena = this.chooseArena(p);
		Location location = spawn = arena == null ? null : this.plugin.store().spawnOf(arena);
		if (spawn == null) {
			this.msg().send(p, "no-spawn", new TagResolver[0]);
			return null;
		}
		OriginalState original = OriginalState.capture(p);
		data.setOriginal(original.toYaml());
		data.setJoins(data.getJoins() + 1);
		this.plugin.dataManager().save(uuid);
		Session session = new Session(original);
		session.arena = arena;
		this.sessions.put(uuid, session);
		p.teleport(spawn);
		return session;
	}

	private String chooseArena(Player p) {
		if (this.forcedArena != null && this.plugin.store().spawnOf(this.forcedArena) != null) {
			return this.forcedArena;
		}
		PartyGroup group = this.plugin.groups().groupOf(p.getUniqueId());
		if (group != null) {
			for (UUID member : group.members()) {
				Session other = this.sessions.get(member);
				if (other == null || other.arena == null || this.plugin.store().spawnOf(other.arena) == null) continue;
				return other.arena;
			}
		}
		return this.plugin.store().pickArena(this::playersIn);
	}

	public boolean arenaBusy(String arena) {
		for (Match m : this.matches.values()) {
			if (m.over || !arena.equals(m.arena)) continue;
			return true;
		}
		return false;
	}

	public int playersInArena(String arena) {
		return this.playersIn(arena);
	}

	private int playersIn(String arena) {
		int n = 0;
		for (Session session : this.sessions.values()) {
			if (!arena.equals(session.arena)) continue;
			++n;
		}
		return n;
	}

	public Location arenaSpawn(UUID uuid) {
		Location loc;
		Session session = this.sessions.get(uuid);
		if (session != null && session.arena != null && (loc = this.plugin.store().spawnOf(session.arena)) != null) {
			return loc;
		}
		String fallback = this.plugin.store().pickArena(this::playersIn);
		return fallback == null ? null : this.plugin.store().spawnOf(fallback);
	}

	public void chooseKit(Player p, String id) {
		KitManager.Kit kit = this.plugin.kits().get(id);
		if (kit == null) {
			this.msg().send(p, "kit-not-found", Messages.tag("kit", id));
			return;
		}
		UUID uuid = p.getUniqueId();
		if (this.matches.containsKey(uuid)) {
			this.msg().send(p, "match-kit-locked", new TagResolver[0]);
			return;
		}
		PartyGroup group = this.plugin.groups().groupOf(uuid);
		if (group == null) {
			this.applyKit(p, kit, false);
			return;
		}
		if (!group.isLeader(uuid)) {
			this.msg().send(p, "kit-leader-only", new TagResolver[0]);
			return;
		}
		int given = 0;
		for (UUID member : group.members()) {
			Player target = Bukkit.getPlayer(member);
			if (target == null || !target.isOnline() || !this.applyKit(target, kit, false)) continue;
			++given;
			if (member.equals(uuid)) continue;
			this.msg().send(target, "kit-party-selected", Messages.tag("leader", p.getName()), Messages.mini("kit", kit.displayName()));
		}
		if (group.size() > 1) {
			this.msg().send(p, "kit-applied-party", Messages.mini("kit", kit.displayName()), Messages.tag("count", given));
		}
	}

	private boolean applyKit(Player p, KitManager.Kit kit, boolean match) {
		long remaining;
		PartyPlayerData data = this.requireData(p);
		if (data == null) {
			return false;
		}
		UUID uuid = p.getUniqueId();
		long now = System.currentTimeMillis();
		Session session = this.sessions.get(uuid);
		if (!match && session != null && !session.builder && (remaining = this.cooldownRemaining(p, now)) > 0L) {
			long seconds = (remaining + 999L) / 1000L;
			this.msg().send(p, "kit-cooldown", Messages.tag("seconds", seconds), Messages.tag("time", this.formatDuration(seconds)));
			return false;
		}
		if (session == null && (session = this.begin(p, data)) == null) {
			return false;
		}
		session.kit = kit.id();
		session.builder = false;
		this.resetPlayer(p);
		InventoryUtil.apply(p.getInventory(), kit.items());
		p.setGameMode(this.kitMode());
		if (!match) {
			this.startCooldown(p, now);
			this.msg().send(p, "kit-selected", Messages.mini("kit", kit.displayName()));
		}
		return true;
	}

	public boolean inMatch(UUID uuid) {
		return this.matches.containsKey(uuid);
	}

	public boolean canFight(UUID uuid) {
		Match m = this.matches.get(uuid);
		return m == null || m.started && !m.over;
	}

	public boolean sameTeam(UUID a, UUID b) {
		Match m = this.matches.get(a);
		return m != null && m.teams && m == this.matches.get(b) && m.teamOf.getOrDefault(a, 0).equals(m.teamOf.getOrDefault(b, 1));
	}

	public boolean matchDropsItems(UUID uuid) {
		Match m = this.matches.get(uuid);
		return m != null && m.dropItems;
	}

	public void startMatch(Player leader, String kitId) {
		String chosen;
		UUID uuid = leader.getUniqueId();
		PartyGroup group = this.plugin.groups().groupOf(uuid);
		if (group == null) {
			this.msg().send(leader, "group-not-in", new TagResolver[0]);
			return;
		}
		if (!group.isLeader(uuid)) {
			this.msg().send(leader, "group-not-leader", new TagResolver[0]);
			return;
		}
		if (this.matches.containsKey(uuid)) {
			this.msg().send(leader, "match-already", new TagResolver[0]);
			return;
		}
		String string = chosen = kitId != null ? kitId : group.getKit();
		if (chosen == null) {
			this.msg().send(leader, "match-no-kit", new TagResolver[0]);
			return;
		}
		KitManager.Kit kit = this.plugin.kits().get(chosen);
		if (kit == null) {
			this.msg().send(leader, "kit-not-found", Messages.tag("kit", chosen));
			return;
		}
		group.setKit(kit.id());
		ArrayList<Player> players = new ArrayList<Player>();
		for (UUID member : group.members()) {
			Player p = Bukkit.getPlayer(member);
			if (p == null || !p.isOnline()) continue;
			players.add(p);
		}
		if (players.size() < 2) {
			this.msg().send(leader, "match-need-players", new TagResolver[0]);
			return;
		}
		group.balanceTeams();
		if (group.isTeams()) {
			HashSet<Integer> sides = new HashSet<Integer>();
			for (Player p : players) {
				sides.add(group.team(p.getUniqueId()));
			}
			if (sides.size() < 2) {
				this.msg().send(leader, "match-need-teams", new TagResolver[0]);
				return;
			}
		}
		this.launch(leader, players, kit, group);
	}

	public boolean canStartRanked(String kitId) {
		return this.plugin.store().isOpen() && this.plugin.store().pickFreeArena(kitId, this::arenaBusy) != null;
	}

	public boolean startRanked(Player a, Player b, KitManager.Kit kit) {
		for (Player p : List.of(a, b)) {
			UUID id = p.getUniqueId();
			if (p.isOnline() && !this.matches.containsKey(id) && !this.sessions.containsKey(id) && !this.plugin.groups().inGroup(id)) continue;
			return false;
		}
		return this.launch(a, List.of(a, b), kit, null);
	}

	private void fail(boolean toAll, Player leader, List<Player> players, String key, TagResolver ... tags) {
		if (toAll) {
			for (Player p : players) {
				this.msg().send(p, key, tags);
			}
		} else {
			this.msg().send(leader, key, tags);
		}
	}

	private boolean launch(Player leader, List<Player> players, KitManager.Kit kit, PartyGroup group) {
		Location spawn;
		boolean queue = group == null;
		YamlConfiguration cfg = this.plugin.settings().config();
		if (this.plugin.store().spawnableArenas().isEmpty()) {
			this.fail(queue, leader, players, "no-spawn", new TagResolver[0]);
			return false;
		}
		if (this.plugin.store().arenasFor(kit.id()).isEmpty()) {
			this.fail(queue, leader, players, "match-no-arena-for-kit", Messages.mini("kit", kit.displayName()));
			return false;
		}
		String arena = this.plugin.store().pickFreeArena(kit.id(), this::arenaBusy);
		Location location = spawn = arena == null ? null : this.plugin.store().spawnOf(arena);
		if (spawn == null) {
			this.fail(queue, leader, players, "match-no-free-arena", new TagResolver[0]);
			return false;
		}
		Match match = new Match();
		match.kitId = kit.id();
		match.arena = arena;
		match.teams = !queue && group.isTeams();
		match.dropItems = queue ? cfg.getBoolean("ranked.drop-items", false) : group.isDropItems();
		match.rounds = queue ? Math.max(1, cfg.getInt("ranked.rounds", 3)) : Math.max(1, group.getRounds());
		match.endless = !queue && players.size() == 2 && cfg.getBoolean("match.two-player-endless", false);
		match.ranked = queue && players.size() == 2 && cfg.getBoolean("ranked.enabled", true);
		ArrayList<Player> joined = new ArrayList<Player>();
		this.forcedArena = arena;
		try {
			for (Player p : players) {
				boolean wasIn = this.sessions.containsKey(p.getUniqueId());
				if (!this.applyKit(p, kit, true)) {
					for (Player done : joined) {
						this.leaveQuietly(done);
					}
					this.fail(queue, leader, players, "match-start-failed", new TagResolver[0]);
					boolean bl = false;
					return bl;
				}
				Session session = this.sessions.get(p.getUniqueId());
				if (session != null) {
					session.arena = arena;
				}
				if (wasIn) {
					p.teleport(spawn);
				}
				joined.add(p);
				match.all.add(p.getUniqueId());
				match.teamOf.put(p.getUniqueId(), queue ? 0 : group.team(p.getUniqueId()));
				if (!match.ranked) continue;
				match.rankedPlayers.add(p.getUniqueId());
				match.names.put(p.getUniqueId(), p.getName());
			}
		}
		finally {
			this.forcedArena = null;
		}
		for (UUID id : match.all) {
			this.matches.put(id, match);
		}
		if (spawn.getWorld() != null && !spawn.getWorld().getPVP()) {
			this.plugin.getLogger().warning("PvP er sl\u00e5et FRA i verdenen '" + spawn.getWorld().getName() + "'. Spillere kan ikke sl\u00e5 hinanden f\u00f8r du sl\u00e5r det til (fx /mvm set pvp true eller match.force-world-pvp: true i config.yml).");
			if (this.plugin.settings().config().getBoolean("match.force-world-pvp", false)) {
				spawn.getWorld().setPVP(true);
			} else if (!queue) {
				leader.sendMessage(this.plugin.messages().component("match-pvp-off", new TagResolver[0]));
			}
		}
		this.debug("Match startet: kit=" + match.kitId + ", arena=" + match.arena + ", teams=" + match.teams + ", spillere=" + match.all.size());
		for (Player p : joined) {
			this.msg().send(p, "match-starting", Messages.mini("kit", kit.displayName()));
		}
		this.beginRound(match);
		return true;
	}

	private void beginRound(final Match m) {
		if (m.over) {
			return;
		}
		++m.round;
		m.started = false;
		m.alive.clear();
		Location spawn = this.plugin.store().spawnOf(m.arena);
		KitManager.Kit kit = this.plugin.kits().get(m.kitId);
		for (UUID id : new ArrayList<UUID>(m.all)) {
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline()) continue;
			m.alive.add(id);
			try {
				if (p.isDead()) {
					p.spigot().respawn();
				}
				if (p.getGameMode() == GameMode.SPECTATOR) {
					p.setSpectatorTarget(null);
				}
				this.plugin.listener().clearPlaced(id);
				this.plugin.listener().clearDropped(id);
				this.resetPlayer(p);
				if (kit != null) {
					InventoryUtil.apply(p.getInventory(), kit.items());
				}
				p.setGameMode(this.kitMode());
				if (spawn != null) {
					p.teleport(spawn);
				}
				if (m.rounds <= 1 && !m.endless) continue;
				this.msg().send(p, "match-round-start", Messages.tag("round", m.round), Messages.tag("rounds", m.endless ? "\u221e" : String.valueOf(m.rounds)));
			}
			catch (Throwable ex) {
				this.plugin.getLogger().log(Level.SEVERE, "Fejl ved ops\u00e6tning af " + p.getName() + " i runde " + m.round + ": " + String.valueOf(ex), ex);
			}
		}
		this.debug("Runde " + m.round + " opsat, alive=" + m.alive.size() + ", nedt\u00e6lling starter");
		final int countdown = Math.max(0, this.plugin.settings().config().getInt("match.countdown-seconds", 5));
		if (m.task != null) {
			m.task.cancel();
		}
		m.task = new BukkitRunnable(){
			private int left = countdown;

			@Override
			public void run() {
				try {
					this.tickCountdown();
				}
				catch (Throwable ex) {
					PartyManager.this.plugin.getLogger().log(Level.SEVERE, "Fejl i nedt\u00e6lling: " + String.valueOf(ex), ex);
					m.started = !m.over;
					this.cancel();
				}
			}

			private void tickCountdown() {
				if (m.over) {
					this.cancel();
					return;
				}
				if (this.left <= 0) {
					m.started = true;
					this.cancel();
					PartyManager.this.safeBroadcast(m, "match-started", new TagResolver[0]);
					PartyManager.this.debug("Runde " + m.round + " er i gang (alive=" + m.alive.size() + ", teams=" + m.teams + ")");
					PartyManager.this.checkRoundOver(m);
					return;
				}
				int shown = this.left--;
				PartyManager.this.safeBroadcast(m, "match-countdown", Messages.tag("seconds", shown));
			}
		}.runTaskTimer(this.plugin, 0L, 20L);
	}

	private void safeBroadcast(Match m, String key, TagResolver ... tags) {
		try {
			this.broadcast(m, key, tags);
		}
		catch (RuntimeException ex) {
			this.plugin.getLogger().log(Level.WARNING, "Kunne ikke sende besked '" + key + "': " + ex.getMessage(), ex);
		}
	}

	public void debug(String text) {
		if (this.plugin.settings().config().getBoolean("match.debug", false)) {
			this.plugin.getLogger().info("[match-debug] " + text);
		}
	}

	private void broadcast(Match m, String key, TagResolver ... tags) {
		for (UUID id : m.all) {
			Player p = Bukkit.getPlayer(id);
			if (p == null) continue;
			this.msg().send(p, key, tags);
		}
	}

	private static String teamName(int team) {
		return team == 1 ? "Bl\u00e5" : "R\u00f8d";
	}

	public void matchDeath(Player p) {
		this.matchDeath(p, null);
	}

	public void matchDeath(Player p, Player killer) {
		Match m = this.matches.get(p.getUniqueId());
		if (m == null || m.over) {
			return;
		}
		if (!m.alive.remove(p.getUniqueId())) {
			return;
		}
		if (m.ranked && m.started) {
			m.deaths.merge(p.getUniqueId(), 1, Integer::sum);
			if (killer != null && !killer.getUniqueId().equals(p.getUniqueId()) && m.rankedPlayers.contains(killer.getUniqueId())) {
				m.kills.merge(killer.getUniqueId(), 1, Integer::sum);
			}
		}
		this.debug(p.getName() + " d\u00f8de. Tilbage i live: " + m.alive.size() + ", started=" + m.started);
		this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
			if (p.isOnline() && p.isDead()) {
				p.spigot().respawn();
			}
		});
		this.retargetSpectators(m);
		this.checkRoundOver(m);
	}

	public void matchRespawn(Player p) {
		UUID id = p.getUniqueId();
		Match m = this.matches.get(id);
		if (m == null || m.over) {
			return;
		}
		this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, () -> {
			if (!p.isOnline() || this.matches.get(id) != m || m.over || m.alive.contains(id)) {
				return;
			}
			p.setGameMode(GameMode.SPECTATOR);
			Player target = this.firstAlive(m);
			if (target != null) {
				p.setSpectatorTarget(target);
			}
			this.msg().send(p, "match-spectating", new TagResolver[0]);
		});
	}

	private Player firstAlive(Match m) {
		for (UUID id : m.alive) {
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline() || p.isDead()) continue;
			return p;
		}
		return null;
	}

	private void retargetSpectators(Match m) {
		Player target = this.firstAlive(m);
		if (target == null) {
			return;
		}
		for (UUID id : m.all) {
			Player cp;
			Entity current;
			Player s;
			if (m.alive.contains(id) || (s = Bukkit.getPlayer(id)) == null || s.isDead() || s.getGameMode() != GameMode.SPECTATOR || (current = s.getSpectatorTarget()) instanceof Player && m.alive.contains((cp = (Player)current).getUniqueId())) continue;
			s.setSpectatorTarget(target);
		}
	}

	public void cancelMatch(PartyGroup group) {
		for (UUID member : group.members()) {
			Match m = this.matches.get(member);
			if (m == null) continue;
			m.over = true;
			if (m.task != null) {
				m.task.cancel();
			}
			for (UUID id : m.all) {
				this.matches.remove(id, m);
			}
		}
	}

	private void onMatchLeave(UUID uuid) {
		boolean enough;
		Match m = this.matches.remove(uuid);
		if (m == null || m.over) {
			return;
		}
		m.alive.remove(uuid);
		m.all.remove(uuid);
		if (m.ranked && m.forfeit == null && this.plugin.isEnabled() && m.rankedPlayers.contains(uuid)) {
			m.forfeit = uuid;
		}
		if (m.teams) {
			HashSet<Integer> sides = new HashSet<Integer>();
			for (UUID id : m.all) {
				sides.add(m.teamOf.getOrDefault(id, 0));
			}
			enough = sides.size() >= 2;
		} else {
			boolean bl = enough = m.all.size() >= 2;
		}
		if (!enough) {
			this.endMatch(m);
			return;
		}
		this.retargetSpectators(m);
		this.checkRoundOver(m);
	}

	private void checkRoundOver(Match m) {
		boolean decided;
		if (m.over || !m.started) {
			return;
		}
		Integer winnerTeam = null;
		UUID winner = null;
		if (m.teams) {
			HashSet<Integer> sides = new HashSet<Integer>();
			for (UUID id : m.alive) {
				sides.add(m.teamOf.getOrDefault(id, 0));
			}
			if (sides.size() > 1) {
				return;
			}
			if (sides.size() == 1) {
				winnerTeam = (Integer)sides.iterator().next();
			}
		} else {
			if (m.alive.size() > 1) {
				return;
			}
			if (!m.alive.isEmpty()) {
				winner = m.alive.iterator().next();
			}
		}
		m.started = false;
		if (winnerTeam != null) {
			int n = winnerTeam;
			m.teamWins[n] = m.teamWins[n] + 1;
			if (m.rounds > 1 || m.endless) {
				this.broadcast(m, "match-round-won", Messages.tag("round", m.round), Messages.tag("winner", PartyManager.teamName(winnerTeam) + " hold"));
			}
		} else if (winner != null) {
			m.wins.merge(winner, 1, Integer::sum);
			if (m.rounds > 1 || m.endless) {
				this.broadcast(m, "match-round-won", Messages.tag("round", m.round), Messages.tag("winner", this.nameOf(winner)));
			}
		} else if (m.rounds > 1 || m.endless) {
			this.broadcast(m, "match-round-draw", Messages.tag("round", m.round));
		}
		boolean bl = decided = m.ranked && winner != null && m.wins.getOrDefault(winner, 0) * 2 > m.rounds;
		if (decided || !m.endless && m.round >= m.rounds || !this.plugin.isEnabled()) {
			this.endMatch(m);
			return;
		}
		long delay = Math.max(1L, this.plugin.settings().config().getLong("match.round-delay-seconds", 4L)) * 20L;
		m.task = this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> this.beginRound(m), delay);
	}

	private String nameOf(UUID id) {
		Player p = Bukkit.getPlayer(id);
		return p != null ? p.getName() : String.valueOf(Bukkit.getOfflinePlayer(id).getName());
	}

	private void endMatch(Match m) {
		if (m.over) {
			return;
		}
		m.over = true;
		m.started = false;
		if (m.task != null) {
			m.task.cancel();
		}
		if (!this.plugin.isEnabled()) {
			return;
		}
		if (m.ranked) {
			this.recordRanked(m);
		}
		if (m.teams) {
			int a = m.teamWins[0];
			int b = m.teamWins[1];
			if (a == b) {
				this.broadcast(m, "match-no-winner", new TagResolver[0]);
			} else {
				this.broadcast(m, "match-final-team", Messages.tag("team", PartyManager.teamName(a > b ? 0 : 1)), Messages.tag("score", Math.max(a, b) + "-" + Math.min(a, b)));
			}
		} else {
			int best = 0;
			UUID bestId = null;
			boolean tie = false;
			for (Map.Entry<UUID, Integer> e : m.wins.entrySet()) {
				if (e.getValue() > best) {
					best = e.getValue();
					bestId = e.getKey();
					tie = false;
					continue;
				}
				if (e.getValue() != best) continue;
				tie = true;
			}
			if (bestId == null || tie) {
				this.broadcast(m, "match-no-winner", new TagResolver[0]);
			} else {
				this.broadcast(m, "match-final-player", Messages.tag("player", this.nameOf(bestId)), Messages.tag("score", best + "/" + m.rounds));
			}
		}
		long delay = Math.max(1L, this.plugin.settings().config().getLong("match.return-delay-seconds", 3L)) * 20L;
		this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, () -> this.finishMatch(m), delay);
	}

	private void recordRanked(Match m) {
		int wb;
		int wa;
		if (m.recorded || m.rankedPlayers.size() != 2) {
			return;
		}
		m.recorded = true;
		UUID a = m.rankedPlayers.get(0);
		UUID b = m.rankedPlayers.get(1);
		StatsManager.Outcome outcome = m.forfeit != null ? (m.forfeit.equals(a) ? StatsManager.Outcome.SECOND_WINS : StatsManager.Outcome.FIRST_WINS) : ((wa = m.wins.getOrDefault(a, 0).intValue()) > (wb = m.wins.getOrDefault(b, 0).intValue()) ? StatsManager.Outcome.FIRST_WINS : (wb > wa ? StatsManager.Outcome.SECOND_WINS : StatsManager.Outcome.DRAW));
		StatsManager.Outcome result = outcome;
		this.plugin.stats().recordDuel(a, m.names.get(a), m.kills.getOrDefault(a, 0), m.deaths.getOrDefault(a, 0), b, m.names.get(b), m.kills.getOrDefault(b, 0), m.deaths.getOrDefault(b, 0), result, (first, second) -> {
			this.announceRanked((StatsManager.Change)first, (StatsManager.Change)second, result == StatsManager.Outcome.FIRST_WINS ? 1 : (result == StatsManager.Outcome.SECOND_WINS ? -1 : 0));
			this.announceRanked((StatsManager.Change)second, (StatsManager.Change)first, result == StatsManager.Outcome.SECOND_WINS ? 1 : (result == StatsManager.Outcome.FIRST_WINS ? -1 : 0));
		});
	}

	private void announceRanked(StatsManager.Change own, StatsManager.Change other, int result) {
		Player p = Bukkit.getPlayer(own.after().uuid());
		if (p == null || !p.isOnline()) {
			return;
		}
		int delta = own.delta();
		this.msg().send(p, result > 0 ? "ranked-win" : (result < 0 ? "ranked-loss" : "ranked-draw"), Messages.tag("change", (delta >= 0 ? "+" : "") + delta), Messages.tag("elo", own.after().elo()), Messages.tag("opponent", other.after().name()));
	}

	private void finishMatch(Match m) {
		for (UUID id : new ArrayList<UUID>(m.all)) {
			this.matches.remove(id, m);
			this.plugin.listener().clearPlaced(id);
			this.plugin.listener().clearDropped(id);
		}
		for (UUID id : new ArrayList<UUID>(m.all)) {
			Player p = Bukkit.getPlayer(id);
			if (p == null || !p.isOnline()) continue;
			if (p.isDead()) {
				p.spigot().respawn();
			}
			if (!this.leaveQuietly(p)) continue;
			this.msg().send(p, "match-over", new TagResolver[0]);
		}
	}

	public void save(Player p) {
		Session session = this.sessions.get(p.getUniqueId());
		if (session == null) {
			this.msg().send(p, "not-in-party", new TagResolver[0]);
			return;
		}
		this.doSave(p, session, false);
	}

	public void edit(Player p) {
		PartyPlayerData data = this.requireData(p);
		if (data == null) {
			return;
		}
		Session session = this.sessions.get(p.getUniqueId());
		if (session == null) {
			session = this.begin(p, data);
			if (session == null) {
				return;
			}
			this.enterBuilder(p, data, session);
			this.msg().send(p, "joined", new TagResolver[0]);
			return;
		}
		if (session.builder) {
			this.msg().send(p, "already-in-builder", new TagResolver[0]);
			return;
		}
		this.enterBuilder(p, data, session);
		this.msg().send(p, "edit-started", new TagResolver[0]);
	}

	public void restock(Player p) {
		PartyPlayerData data = this.requireData(p);
		if (data == null) {
			return;
		}
		Session session = this.sessions.get(p.getUniqueId());
		if (session == null) {
			this.msg().send(p, "not-in-party", new TagResolver[0]);
			return;
		}
		if (session.builder) {
			this.msg().send(p, "restock-in-builder", new TagResolver[0]);
			return;
		}
		ItemStack[] kit = this.activeKit(session, data);
		if (kit == null) {
			this.msg().send(p, "no-kit", new TagResolver[0]);
			return;
		}
		long now = System.currentTimeMillis();
		long remaining = this.cooldownRemaining(p, now);
		if (remaining > 0L) {
			long seconds = (remaining + 999L) / 1000L;
			this.msg().send(p, "restock-cooldown", Messages.tag("seconds", seconds), Messages.tag("time", this.formatDuration(seconds)));
			return;
		}
		boolean dropped = this.giveKit(p, kit);
		this.startCooldown(p, now);
		data.setRestocks(data.getRestocks() + 1);
		this.plugin.dataManager().save(p.getUniqueId());
		this.msg().send(p, "restocked", new TagResolver[0]);
		if (dropped) {
			this.msg().send(p, "overflow-dropped", new TagResolver[0]);
		}
	}

	public void leave(Player p) {
		if (!this.sessions.containsKey(p.getUniqueId())) {
			this.msg().send(p, "not-in-party", new TagResolver[0]);
			return;
		}
		this.leaveQuietly(p);
		this.msg().send(p, "left", new TagResolver[0]);
	}

	public boolean leaveQuietly(Player p) {
		Session session = this.sessions.remove(p.getUniqueId());
		if (session == null) {
			return false;
		}
		this.plugin.listener().clearPlaced(p.getUniqueId());
		this.plugin.listener().clearDropped(p.getUniqueId());
		this.restore(p, session.original);
		PartyPlayerData data = this.plugin.dataManager().get(p.getUniqueId());
		if (data != null) {
			data.setOriginal(null);
			this.plugin.dataManager().save(p.getUniqueId());
		}
		this.onMatchLeave(p.getUniqueId());
		return true;
	}

	public boolean removeWithMessage(Player p, String messageKey) {
		if (!this.leaveQuietly(p)) {
			return false;
		}
		this.msg().send(p, messageKey, new TagResolver[0]);
		return true;
	}

	public void kickAll(String messageKey) {
		for (UUID uuid : new ArrayList<UUID>(this.sessions.keySet())) {
			Player p = Bukkit.getPlayer(uuid);
			if (p == null) {
				this.sessions.remove(uuid);
				continue;
			}
			this.removeWithMessage(p, messageKey);
		}
	}

	public void recover(Player p) {
		if (this.sessions.containsKey(p.getUniqueId())) {
			return;
		}
		PartyPlayerData data = this.plugin.dataManager().get(p.getUniqueId());
		if (data == null || data.getOriginal() == null) {
			return;
		}
		String yaml = data.getOriginal();
		data.setOriginal(null);
		this.plugin.dataManager().save(p.getUniqueId());
		try {
			this.restore(p, OriginalState.fromYaml(yaml));
			this.msg().send(p, "restored-after-crash", new TagResolver[0]);
		}
		catch (Exception ex) {
			this.plugin.getLogger().log(Level.SEVERE, "Kunne ikke gendanne original tilstand for " + p.getName() + ": " + ex.getMessage(), ex);
		}
	}

	public void giveKitOnRespawn(Player p) {
		Session session = this.sessions.get(p.getUniqueId());
		PartyPlayerData data = this.plugin.dataManager().get(p.getUniqueId());
		if (session == null || session.builder || data == null || this.matches.containsKey(p.getUniqueId())) {
			return;
		}
		ItemStack[] kit = this.activeKit(session, data);
		if (kit != null) {
			this.giveKit(p, kit);
		}
	}

	private void doSave(Player p, Session session, boolean force) {
		long remaining;
		PartyPlayerData data = this.requireData(p);
		if (data == null) {
			return;
		}
		if (!session.builder) {
			this.msg().send(p, "not-in-builder", new TagResolver[0]);
			return;
		}
		YamlConfiguration config = this.plugin.settings().config();
		boolean cooldownOnSave = config.getBoolean("restock.cooldown-on-save", true);
		if (cooldownOnSave && !force && (remaining = this.cooldownRemaining(p, System.currentTimeMillis())) > 0L) {
			long seconds = (remaining + 999L) / 1000L;
			this.msg().send(p, "save-cooldown", Messages.tag("seconds", seconds), Messages.tag("time", this.formatDuration(seconds)));
			return;
		}
		KitSanitizer.Result result = KitSanitizer.sanitize(InventoryUtil.snapshot(p.getInventory()), config);
		if (result.empty() && config.getBoolean("kit.reject-empty", true)) {
			this.msg().send(p, "kit-empty", new TagResolver[0]);
			return;
		}
		data.setKitItems(result.items());
		data.setLastSave(System.currentTimeMillis());
		this.plugin.dataManager().save(p.getUniqueId());
		if (result.changed() > 0) {
			this.msg().send(p, "items-removed", Messages.tag("count", result.changed()));
		}
		session.builder = false;
		session.kit = null;
		this.resetPlayer(p);
		InventoryUtil.apply(p.getInventory(), result.items());
		p.setGameMode(this.kitMode());
		if (cooldownOnSave) {
			this.startCooldown(p, System.currentTimeMillis());
		}
		this.msg().send(p, "saved", new TagResolver[0]);
	}

	private void enterBuilder(Player p, PartyPlayerData data, Session session) {
		session.builder = true;
		session.builderSince = System.currentTimeMillis();
		this.resetPlayer(p);
		ItemStack[] kit = this.activeKit(session, data);
		InventoryUtil.apply(p.getInventory(), kit == null ? new ItemStack[41] : kit);
		p.setGameMode(GameMode.CREATIVE);
	}

	private boolean giveKit(Player p, ItemStack[] kit) {
		YamlConfiguration config = this.plugin.settings().config();
		boolean dropped = false;
		if ("FILL".equalsIgnoreCase(config.getString("restock.mode", "REPLACE"))) {
			List<ItemStack> left = InventoryUtil.fill(p.getInventory(), kit);
			if (!left.isEmpty() && config.getBoolean("restock.drop-overflow", true)) {
				for (ItemStack item : left) {
					p.getWorld().dropItemNaturally(p.getLocation(), item);
				}
				dropped = true;
			}
		} else {
			InventoryUtil.apply(p.getInventory(), kit);
		}
		this.heal(p);
		p.setGameMode(this.kitMode());
		return dropped;
	}

	private void restore(Player p, OriginalState original) {
		Location loc;
		if (p.getGameMode() == GameMode.SPECTATOR) {
			p.setSpectatorTarget(null);
		}
		if ((loc = original.location()) != null) {
			p.teleport(loc);
		}
		original.apply(p);
		p.setGameMode(original.gameMode());
	}

	private void resetPlayer(Player p) {
		for (PotionEffect effect : p.getActivePotionEffects()) {
			p.removePotionEffect(effect.getType());
		}
		p.setLevel(0);
		p.setExp(0.0f);
		this.heal(p);
	}

	private void heal(Player p) {
		AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
		p.setHealth(max == null ? 20.0 : max.getValue());
		p.setFoodLevel(20);
		p.setSaturation(20.0f);
		p.setFireTicks(0);
		p.setFallDistance(0.0f);
	}

	private GameMode kitMode() {
		try {
			return GameMode.valueOf(this.plugin.settings().config().getString("kit-gamemode", "SURVIVAL").toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return GameMode.SURVIVAL;
		}
	}

	private ItemStack[] activeKit(Session session, PartyPlayerData data) {
		KitManager.Kit preset;
		if (session.kit != null && (preset = this.plugin.kits().get(session.kit)) != null) {
			return preset.items();
		}
		return this.safeKit(data);
	}

	private ItemStack[] safeKit(PartyPlayerData data) {
		try {
			return data.kitItems();
		}
		catch (RuntimeException ex) {
			this.plugin.getLogger().log(Level.WARNING, "Kit for " + String.valueOf(data.getUuid()) + " kunne ikke l\u00e6ses: " + ex.getMessage());
			return null;
		}
	}

	private PartyPlayerData requireData(Player p) {
		PartyPlayerData data = this.plugin.dataManager().get(p.getUniqueId());
		if (data == null) {
			this.msg().send(p, "data-not-loaded", new TagResolver[0]);
		}
		return data;
	}

	private Messages msg() {
		return this.plugin.messages();
	}

	private void tick() {
		YamlConfiguration config = this.plugin.settings().config();
		boolean limited = config.getBoolean("time-limit.enabled", false);
		long limitSeconds = config.getLong("time-limit.seconds", 300L);
		long now = System.currentTimeMillis();
		this.cooldownUntil.values().removeIf(until -> until <= now);
		for (UUID uuid : new ArrayList<UUID>(this.sessions.keySet())) {
			Location spawn;
			Session session = this.sessions.get(uuid);
			Player p = Bukkit.getPlayer(uuid);
			if (session == null) continue;
			if (p == null || !p.isOnline()) {
				this.sessions.remove(uuid);
				continue;
			}
			if (session.arena != null && this.plugin.store().hasRegion(session.arena) && !this.plugin.store().inRegion(session.arena, p.getLocation()) && (spawn = this.plugin.store().spawnOf(session.arena)) != null) {
				p.teleport(spawn);
			}
			if (!limited || !session.builder) continue;
			long left = limitSeconds - (now - session.builderSince) / 1000L;
			if (left <= 0L) {
				this.timeout(p, session, config.getString("time-limit.on-timeout", "SAVE"));
				continue;
			}
			this.msg().actionBar(p, "time-left", Messages.tag("seconds", left));
		}
	}

	private void timeout(Player p, Session session, String action) {
		this.msg().send(p, "time-up", new TagResolver[0]);
		if ("SAVE".equalsIgnoreCase(action)) {
			this.doSave(p, session, true);
			if (!session.builder) {
				return;
			}
		}
		this.leaveQuietly(p);
		this.msg().send(p, "left", new TagResolver[0]);
	}

	private static final class Session {
		private final OriginalState original;
		private boolean builder;
		private long builderSince;
		private String kit;
		private String arena;

		private Session(OriginalState original) {
			this.original = original;
		}
	}

	private static final class Match {
		private final Set<UUID> all = new LinkedHashSet<UUID>();
		private final Set<UUID> alive = new LinkedHashSet<UUID>();
		private final Map<UUID, Integer> teamOf = new HashMap<UUID, Integer>();
		private final Map<UUID, Integer> wins = new HashMap<UUID, Integer>();
		private final int[] teamWins = new int[2];
		private String kitId;
		private String arena;
		private boolean teams;
		private boolean dropItems;
		private int rounds = 1;
		private int round;
		private boolean endless;
		private boolean started;
		private boolean over;
		private BukkitTask task;
		private boolean ranked;
		private final List<UUID> rankedPlayers = new ArrayList<UUID>();
		private final Map<UUID, String> names = new HashMap<UUID, String>();
		private final Map<UUID, Integer> kills = new HashMap<UUID, Integer>();
		private final Map<UUID, Integer> deaths = new HashMap<UUID, Integer>();
		private UUID forfeit;
		private boolean recorded;

		private Match() {
		}
	}
}