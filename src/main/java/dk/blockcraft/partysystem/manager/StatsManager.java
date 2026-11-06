package dk.blockcraft.partysystem.manager;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.data.Database;
import dk.blockcraft.partysystem.model.PartyStats;
import dk.blockcraft.partysystem.util.Messages;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class StatsManager {
	private final PartySystem plugin;
	private final Map<UUID, PartyStats> cache = new ConcurrentHashMap<UUID, PartyStats>();
	private final AtomicBoolean refreshing = new AtomicBoolean();
	private volatile Map<Top, List<PartyStats>> tops = Map.of();
	private BukkitTask refreshTask;

	public StatsManager(PartySystem plugin) {
		this.plugin = plugin;
	}

	public void start() {
		this.restartTimer();
	}

	public void restartTimer() {
		if (this.refreshTask != null) {
			this.refreshTask.cancel();
		}
		long seconds = Math.max(5L, this.plugin.settings().config().getLong("leaderboard.refresh-seconds", 30L));
		this.refreshTask = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, this::refreshTops, 40L, seconds * 20L);
	}

	public void shutdown() {
		if (this.refreshTask != null) {
			this.refreshTask.cancel();
			this.refreshTask = null;
		}
		this.cache.clear();
	}

	public int startElo() {
		return this.plugin.settings().config().getInt("ranked.start-elo", 1000);
	}

	public PartyStats cached(UUID uuid) {
		return this.cache.get(uuid);
	}

	public PartyStats statsOrFresh(UUID uuid, String name) {
		PartyStats s = this.cache.get(uuid);
		return s != null ? s : PartyStats.fresh(uuid, name, this.startElo());
	}

	public int eloOf(UUID uuid) {
		return this.statsOrFresh(uuid, "").elo();
	}

	public List<PartyStats> top(Top type) {
		List<PartyStats> list = this.tops.get((Object)type);
		return list == null ? List.of() : list;
	}

	public void load(UUID uuid, String name) {
		this.run(() -> {
			try {
				PartyStats s = this.database().loadStats(uuid);
				PartyStats partyStats = s = s == null ? PartyStats.fresh(uuid, name, this.startElo()) : s.withName(name);
				if (Bukkit.getPlayer(uuid) != null) {
					this.cache.put(uuid, s);
				}
			}
			catch (SQLException ex) {
				this.plugin.getLogger().log(Level.SEVERE, "Kunne ikke indl\u00e6se ranked-stats for " + String.valueOf(uuid) + ": " + ex.getMessage(), ex);
			}
		});
	}

	public void unload(UUID uuid) {
		this.cache.remove(uuid);
	}

	public CompletableFuture<PartyStats> fetch(UUID uuid, String name) {
		PartyStats cached = this.cache.get(uuid);
		if (cached != null) {
			return CompletableFuture.completedFuture(cached);
		}
		CompletableFuture<PartyStats> future = new CompletableFuture<PartyStats>();
		boolean ok = this.run(() -> {
			try {
				PartyStats s = this.database().loadStats(uuid);
				future.complete(s != null ? s : PartyStats.fresh(uuid, name, this.startElo()));
			}
			catch (SQLException ex) {
				future.completeExceptionally(ex);
			}
		});
		if (!ok) {
			future.completeExceptionally(new IllegalStateException("PartySystem lukker ned"));
		}
		return future;
	}

	public CompletableFuture<PartyStats> findByName(String name) {
		Player online = Bukkit.getPlayerExact(name);
		if (online != null) {
			return this.fetch(online.getUniqueId(), online.getName());
		}
		OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(name);
		CompletableFuture<PartyStats> future = new CompletableFuture<PartyStats>();
		boolean ok = this.run(() -> {
			try {
				PartyStats s = this.database().findStatsByName(name);
				if (s == null && known != null && (s = this.database().loadStats(known.getUniqueId())) == null) {
					s = PartyStats.fresh(known.getUniqueId(), known.getName() != null ? known.getName() : name, this.startElo());
				}
				future.complete(s);
			}
			catch (SQLException ex) {
				future.completeExceptionally(ex);
			}
		});
		if (!ok) {
			future.completeExceptionally(new IllegalStateException("PartySystem lukker ned"));
		}
		return future;
	}

	public CompletableFuture<Integer> rank(PartyStats stats) {
		if (stats.matches() == 0) {
			return CompletableFuture.completedFuture(-1);
		}
		CompletableFuture<Integer> future = new CompletableFuture<Integer>();
		boolean ok = this.run(() -> {
			try {
				future.complete(this.database().rankOf(stats.elo()));
			}
			catch (SQLException ex) {
				future.completeExceptionally(ex);
			}
		});
		if (!ok) {
			future.completeExceptionally(new IllegalStateException("PartySystem lukker ned"));
		}
		return future;
	}

	public void recordDuel(UUID first, String firstName, int firstKills, int firstDeaths, UUID second, String secondName, int secondKills, int secondDeaths, Outcome outcome, BiConsumer<Change, Change> done) {
		this.run(() -> {
			try {
				PartyStats a = this.current(first, firstName);
				PartyStats b = this.current(second, secondName);
				double expectedA = 1.0 / (1.0 + Math.pow(10.0, (double)(b.elo() - a.elo()) / 400.0));
				double scoreA = switch (outcome.ordinal()) {
					default -> throw new MatchException(null, null);
					case 0 -> 1.0;
					case 1 -> 0.0;
					case 2 -> 0.5;
				};
				int minElo = Math.max(0, this.plugin.settings().config().getInt("ranked.min-elo", 0));
				int newA = Math.max(minElo, a.elo() + (int)Math.round((double)this.kFactor(a) * (scoreA - expectedA)));
				int newB = Math.max(minElo, b.elo() + (int)Math.round((double)this.kFactor(b) * (1.0 - scoreA - (1.0 - expectedA))));
				int resultA = outcome == Outcome.FIRST_WINS ? 1 : (outcome == Outcome.SECOND_WINS ? -1 : 0);
				PartyStats na = StatsManager.applied(a, firstName, newA, resultA, firstKills, firstDeaths);
				PartyStats nb = StatsManager.applied(b, secondName, newB, -resultA, secondKills, secondDeaths);
				this.database().saveStats(na);
				this.database().saveStats(nb);
				this.cache.replace(first, na);
				this.cache.replace(second, nb);
				if (this.plugin.isEnabled()) {
					Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> done.accept(new Change(a, na), new Change(b, nb)));
					this.refreshTops();
				}
			}
			catch (SQLException ex) {
				this.plugin.getLogger().log(Level.SEVERE, "Kunne ikke gemme ranked-resultat: " + ex.getMessage(), ex);
			}
		});
	}

	public CompletableFuture<Void> setElo(UUID uuid, String name, int elo) {
		CompletableFuture<Void> future = new CompletableFuture<Void>();
		boolean ok = this.run(() -> {
			try {
				PartyStats s = this.current(uuid, name);
				int value = Math.max(0, elo);
				PartyStats updated = new PartyStats(uuid, name != null && !name.isBlank() ? name : s.name(), value, Math.max(s.peakElo(), value), s.wins(), s.losses(), s.draws(), s.streak(), s.bestStreak(), s.kills(), s.deaths());
				this.database().saveStats(updated);
				this.cache.replace(uuid, updated);
				future.complete(null);
				this.refreshTops();
			}
			catch (SQLException ex) {
				future.completeExceptionally(ex);
			}
		});
		if (!ok) {
			future.completeExceptionally(new IllegalStateException("PartySystem lukker ned"));
		}
		return future;
	}

	public CompletableFuture<Void> reset(UUID uuid, String name) {
		CompletableFuture<Void> future = new CompletableFuture<Void>();
		boolean ok = this.run(() -> {
			try {
				this.database().deleteStats(uuid);
				this.cache.replace(uuid, PartyStats.fresh(uuid, name, this.startElo()));
				future.complete(null);
				this.refreshTops();
			}
			catch (SQLException ex) {
				future.completeExceptionally(ex);
			}
		});
		if (!ok) {
			future.completeExceptionally(new IllegalStateException("PartySystem lukker ned"));
		}
		return future;
	}

	public void refreshTops() {
		if (!this.plugin.isEnabled() || !this.refreshing.compareAndSet(false, true)) {
			return;
		}
		int limit = Math.max(1, Math.min(50, this.plugin.settings().config().getInt("leaderboard.entries", 10)));
		boolean ok = this.run(() -> {
			try {
				EnumMap<Top, List<PartyStats>> result = new EnumMap<Top, List<PartyStats>>(Top.class);
				for (Top top : Top.values()) {
					result.put(top, this.database().top(top.column(), limit));
				}
				this.tops = result;
			}
			catch (SQLException ex) {
				this.plugin.getLogger().log(Level.WARNING, "Kunne ikke hente toplister: " + ex.getMessage(), ex);
			}
			finally {
				this.refreshing.set(false);
			}
			if (this.plugin.isEnabled()) {
				Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
					if (this.plugin.holograms() != null) {
						this.plugin.holograms().updateAll();
					}
				});
			}
		});
		if (!ok) {
			this.refreshing.set(false);
		}
	}

	public static TagResolver[] tags(PartyStats s) {
		return new TagResolver[]{Messages.tag("player", s.name() == null || s.name().isBlank() ? "?" : s.name()), Messages.tag("elo", s.elo()), Messages.tag("peak_elo", s.peakElo()), Messages.tag("wins", s.wins()), Messages.tag("losses", s.losses()), Messages.tag("draws", s.draws()), Messages.tag("matches", s.matches()), Messages.tag("winrate", s.winRate()), Messages.tag("streak", s.streak()), Messages.tag("best_streak", s.bestStreak()), Messages.tag("kills", s.kills()), Messages.tag("deaths", s.deaths()), Messages.tag("kdr", String.format(Locale.ROOT, "%.2f", s.kdr()))};
	}

	private Database database() {
		return this.plugin.dataManager().database();
	}

	private boolean run(Runnable task) {
		try {
			this.plugin.dataManager().executor().execute(task);
			return true;
		}
		catch (RejectedExecutionException ex) {
			return false;
		}
	}

	private PartyStats current(UUID uuid, String name) throws SQLException {
		PartyStats cached = this.cache.get(uuid);
		if (cached != null) {
			return cached;
		}
		PartyStats s = this.database().loadStats(uuid);
		return s != null ? s : PartyStats.fresh(uuid, name, this.startElo());
	}

	private int kFactor(PartyStats s) {
		YamlConfiguration cfg = this.plugin.settings().config();
		int placement = cfg.getInt("ranked.placement-matches", 10);
		return s.matches() < placement ? cfg.getInt("ranked.placement-k-factor", 48) : cfg.getInt("ranked.k-factor", 32);
	}

	private static PartyStats applied(PartyStats old, String name, int newElo, int result, int kills, int deaths) {
		int wins = old.wins() + (result > 0 ? 1 : 0);
		int losses = old.losses() + (result < 0 ? 1 : 0);
		int draws = old.draws() + (result == 0 ? 1 : 0);
		int streak = result > 0 ? old.streak() + 1 : (result < 0 ? 0 : old.streak());
		return new PartyStats(old.uuid(), name != null && !name.isBlank() ? name : old.name(), newElo, Math.max(old.peakElo(), newElo), wins, losses, draws, streak, Math.max(old.bestStreak(), streak), old.kills() + kills, old.deaths() + deaths);
	}

	public static enum Outcome {
		FIRST_WINS,
		SECOND_WINS,
		DRAW;

	}

	public static enum Top {
		ELO("elo", "elo"),
		WINS("wins", "wins"),
		STREAK("streak", "best_streak"),
		KILLS("kills", "kills");

		private final String id;
		private final String column;

		private Top(String id, String column) {
			this.id = id;
			this.column = column;
		}

		public String id() {
			return this.id;
		}

		public String column() {
			return this.column;
		}

		public int value(PartyStats s) {
			return switch (this.ordinal()) {
				default -> throw new MatchException(null, null);
				case 0 -> s.elo();
				case 1 -> s.wins();
				case 2 -> s.bestStreak();
				case 3 -> s.kills();
			};
		}

		public static Top parse(String text) {
			if (text == null) {
				return null;
			}
			for (Top top : Top.values()) {
				if (!top.id.equalsIgnoreCase(text)) continue;
				return top;
			}
			return null;
		}

		public static String names() {
			StringBuilder sb = new StringBuilder();
			for (Top top : Top.values()) {
				if (sb.length() > 0) {
					sb.append(", ");
				}
				sb.append(top.id);
			}
			return sb.toString();
		}
	}

	public record Change(PartyStats before, PartyStats after) {
		public int delta() {
			return this.after.elo() - this.before.elo();
		}
	}

	public record View(PartyStats stats, int rank) {
	}
}