package dk.blockcraft.partysystem.manager;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.manager.KitManager;
import dk.blockcraft.partysystem.util.Messages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class QueueManager {
	private final PartySystem plugin;
	private final Map<UUID, Entry> queue = new ConcurrentHashMap<UUID, Entry>();
	private BukkitTask task;

	public QueueManager(PartySystem plugin) {
		this.plugin = plugin;
	}

	public void start() {
		this.task = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, this::tick, 20L, 20L);
	}

	public void shutdown() {
		if (this.task != null) {
			this.task.cancel();
			this.task = null;
		}
		this.queue.clear();
	}

	private Messages msg() {
		return this.plugin.messages();
	}

	public boolean isQueued(UUID uuid) {
		return this.queue.containsKey(uuid);
	}

	public int size() {
		return this.queue.size();
	}

	public int size(String kitId) {
		int count = 0;
		for (Entry e : this.queue.values()) {
			if (!e.kitId.equals(kitId)) continue;
			++count;
		}
		return count;
	}

	public void join(Player p, String kitId) {
		UUID uuid = p.getUniqueId();
		YamlConfiguration cfg = this.plugin.settings().config();
		if (!cfg.getBoolean("queue.enabled", true)) {
			this.msg().send(p, "queue-disabled", new TagResolver[0]);
			return;
		}
		if (!this.plugin.store().isOpen() && !p.hasPermission("partysystem.admin")) {
			this.msg().send(p, "closed", new TagResolver[0]);
			return;
		}
		if (this.queue.containsKey(uuid)) {
			this.msg().send(p, "queue-already", new TagResolver[0]);
			return;
		}
		if (this.plugin.groups().inGroup(uuid)) {
			this.msg().send(p, "queue-in-group", new TagResolver[0]);
			return;
		}
		if (this.plugin.manager().inParty(uuid) || this.plugin.manager().inMatch(uuid)) {
			this.msg().send(p, "queue-busy", new TagResolver[0]);
			return;
		}
		KitManager.Kit kit = this.plugin.kits().get(kitId);
		if (kit == null) {
			this.msg().send(p, "kit-not-found", Messages.tag("kit", kitId));
			return;
		}
		if (this.plugin.store().arenasFor(kit.id()).isEmpty()) {
			this.msg().send(p, "match-no-arena-for-kit", Messages.mini("kit", kit.displayName()));
			return;
		}
		if (this.plugin.stats().cached(uuid) == null) {
			this.msg().send(p, "data-not-loaded", new TagResolver[0]);
			return;
		}
		this.queue.put(uuid, new Entry(uuid, kit.id(), System.currentTimeMillis()));
		this.msg().send(p, "queue-joined", Messages.mini("kit", kit.displayName()), Messages.tag("size", this.size(kit.id())));
	}

	public boolean leave(Player p, boolean notify) {
		boolean removed;
		boolean bl = removed = this.queue.remove(p.getUniqueId()) != null;
		if (removed && notify) {
			this.msg().send(p, "queue-left", new TagResolver[0]);
		}
		return removed;
	}

	public void remove(UUID uuid) {
		this.queue.remove(uuid);
	}

	public void status(Player p) {
		Entry e = this.queue.get(p.getUniqueId());
		if (e == null) {
			this.msg().send(p, "queue-not-in", new TagResolver[0]);
			return;
		}
		long now = System.currentTimeMillis();
		KitManager.Kit kit = this.plugin.kits().get(e.kitId);
		this.msg().send(p, "queue-status", Messages.mini("kit", kit != null ? kit.displayName() : e.kitId), Messages.tag("time", this.plugin.manager().formatDuration(this.waited(e, now))), Messages.tag("range", this.describeRange(e, now)));
	}

	private long waited(Entry e, long now) {
		return Math.max(0L, (now - e.since) / 1000L);
	}

	private long range(Entry e, long now) {
		YamlConfiguration cfg = this.plugin.settings().config();
		long base = Math.max(0, cfg.getInt("queue.elo-range.initial", 100));
		long grow = Math.max(0, cfg.getInt("queue.elo-range.expand-per-second", 10));
		long max = cfg.getInt("queue.elo-range.max", 0);
		long range = base + grow * this.waited(e, now);
		return max > 0L ? Math.min(max, range) : range;
	}

	private String describeRange(Entry e, long now) {
		return String.valueOf(this.range(e, now));
	}

	private void tick() {
		long now = System.currentTimeMillis();
		boolean actionbar = this.plugin.settings().config().getBoolean("queue.actionbar", true);
		for (Entry e : new ArrayList<Entry>(this.queue.values())) {
			Player p = Bukkit.getPlayer(e.uuid);
			if (p == null || !p.isOnline()) {
				this.queue.remove(e.uuid);
				continue;
			}
			if (this.plugin.manager().inParty(e.uuid) || this.plugin.manager().inMatch(e.uuid) || this.plugin.groups().inGroup(e.uuid)) {
				this.queue.remove(e.uuid);
				this.msg().send(p, "queue-cancelled", new TagResolver[0]);
				continue;
			}
			if (!actionbar) continue;
			KitManager.Kit kit = this.plugin.kits().get(e.kitId);
			this.msg().actionBar(p, "queue-actionbar", Messages.mini("kit", kit != null ? kit.displayName() : e.kitId), Messages.tag("time", this.plugin.manager().formatDuration(this.waited(e, now))), Messages.tag("range", this.describeRange(e, now)), Messages.tag("size", this.size(e.kitId)));
		}
		LinkedHashMap<String, List<Entry>> byKit = new LinkedHashMap<String, List<Entry>>();
		this.queue.values().stream().sorted(Comparator.comparingLong(en -> en.since)).forEach(en -> byKit.computeIfAbsent(en.kitId, k -> new ArrayList<Entry>()).add(en));
		for (Map.Entry<String, List<Entry>> group : byKit.entrySet()) {
			this.pairUp(group.getKey(), group.getValue(), now);
		}
	}

	private void pairUp(String kitId, List<Entry> list, long now) {
		if (list.size() < 2) {
			return;
		}
		KitManager.Kit kit = this.plugin.kits().get(kitId);
		if (kit == null) {
			for (Entry e : list) {
				this.queue.remove(e.uuid);
			}
			return;
		}
		HashSet<UUID> used = new HashSet<UUID>();
		for (Entry a : list) {
			if (used.contains(a.uuid) || !this.queue.containsKey(a.uuid)) continue;
			Entry best = null;
			long bestDiff = Long.MAX_VALUE;
			for (Entry b : list) {
				long diff;
				if (b == a || used.contains(b.uuid) || !this.queue.containsKey(b.uuid) || (diff = (long)Math.abs(this.plugin.stats().eloOf(a.uuid) - this.plugin.stats().eloOf(b.uuid))) > Math.max(this.range(a, now), this.range(b, now)) || diff >= bestDiff) continue;
				best = b;
				bestDiff = diff;
			}
			if (best == null) continue;
			Player pa = Bukkit.getPlayer(a.uuid);
			Player pb = Bukkit.getPlayer(best.uuid);
			if (pa == null || pb == null) continue;
			if (!this.plugin.manager().canStartRanked(kitId)) {
				for (Entry entry : List.of(a, best)) {
					Player waiting = Bukkit.getPlayer(entry.uuid);
					if (waiting == null || entry.notifiedBusy) continue;
					entry.notifiedBusy = true;
					this.msg().send(waiting, "queue-waiting-arena", new TagResolver[0]);
				}
				return;
			}
			used.add(a.uuid);
			used.add(best.uuid);
			this.queue.remove(a.uuid);
			this.queue.remove(best.uuid);
			if (this.plugin.manager().startRanked(pa, pb, kit)) {
				this.msg().send(pa, "queue-found", Messages.tag("opponent", pb.getName()), Messages.tag("opponent_elo", this.plugin.stats().eloOf(best.uuid)), Messages.mini("kit", kit.displayName()));
				this.msg().send(pb, "queue-found", Messages.tag("opponent", pa.getName()), Messages.tag("opponent_elo", this.plugin.stats().eloOf(a.uuid)), Messages.mini("kit", kit.displayName()));
				continue;
			}
			this.msg().send(pa, "queue-start-failed", new TagResolver[0]);
			this.msg().send(pb, "queue-start-failed", new TagResolver[0]);
		}
	}

	private static final class Entry {
		private final UUID uuid;
		private final String kitId;
		private final long since;
		private boolean notifiedBusy;

		private Entry(UUID uuid, String kitId, long since) {
			this.uuid = uuid;
			this.kitId = kitId;
			this.since = since;
		}
	}
}