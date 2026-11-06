package dk.blockcraft.partysystem.manager;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.model.PartyGroup;
import dk.blockcraft.partysystem.util.Messages;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

public final class GroupManager {
	private static final String[] ENTRIES = new String[16];
	private final PartySystem plugin;
	private final Map<UUID, PartyGroup> groups = new ConcurrentHashMap<UUID, PartyGroup>();
	private final Map<UUID, Invite> invites = new ConcurrentHashMap<UUID, Invite>();
	private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<UUID, Scoreboard>();
	private final Map<UUID, Scoreboard> previous = new ConcurrentHashMap<UUID, Scoreboard>();

	public GroupManager(PartySystem plugin) {
		this.plugin = plugin;
	}

	public PartyGroup groupOf(UUID uuid) {
		return this.groups.get(uuid);
	}

	public boolean inGroup(UUID uuid) {
		return this.groups.containsKey(uuid);
	}

	public int sizeOf(UUID uuid) {
		PartyGroup g = this.groups.get(uuid);
		return g == null ? 0 : g.size();
	}

	public String leaderNameOf(UUID uuid) {
		PartyGroup g = this.groups.get(uuid);
		return g == null ? "" : this.nameOf(g.getLeader());
	}

	public String memberNamesOf(UUID uuid) {
		PartyGroup g = this.groups.get(uuid);
		if (g == null) {
			return "";
		}
		ArrayList<String> names = new ArrayList<String>();
		for (UUID m : g.members()) {
			names.add(this.nameOf(m));
		}
		return String.join((CharSequence)", ", names);
	}

	public int maxSize() {
		return Math.max(2, this.plugin.settings().config().getInt("group.max-size", 8));
	}

	public void create(Player p) {
		UUID uuid = p.getUniqueId();
		if (this.groups.containsKey(uuid)) {
			this.msg().send(p, "group-already-in", new TagResolver[0]);
			return;
		}
		this.plugin.queue().leave(p, true);
		PartyGroup group = new PartyGroup(uuid);
		this.groups.put(uuid, group);
		this.invites.remove(uuid);
		this.msg().send(p, "group-created", new TagResolver[0]);
		this.plugin.menu().giveSword(p);
		this.refresh(group);
	}

	public void invite(Player p, String targetName) {
		PartyGroup group = this.groups.get(p.getUniqueId());
		if (group == null) {
			this.msg().send(p, "group-not-in", new TagResolver[0]);
			return;
		}
		if (this.onlyLeaderInvites() && !group.isLeader(p.getUniqueId())) {
			this.msg().send(p, "group-not-leader", new TagResolver[0]);
			return;
		}
		Player target = Bukkit.getPlayerExact(targetName);
		if (target == null) {
			this.msg().send(p, "player-not-found", Messages.tag("player", targetName));
			return;
		}
		if (target.getUniqueId().equals(p.getUniqueId())) {
			this.msg().send(p, "group-invite-self", new TagResolver[0]);
			return;
		}
		if (this.groups.containsKey(target.getUniqueId())) {
			this.msg().send(p, "group-invite-in-party", Messages.tag("player", target.getName()));
			return;
		}
		if (group.size() >= this.maxSize()) {
			this.msg().send(p, "group-full", Messages.tag("max", this.maxSize()));
			return;
		}
		long now = System.currentTimeMillis();
		Invite existing = this.invites.get(target.getUniqueId());
		if (existing != null && existing.group() == group && existing.expiresAt() > now) {
			this.msg().send(p, "group-invite-pending", Messages.tag("player", target.getName()));
			return;
		}
		long seconds = Math.max(5L, this.plugin.settings().config().getLong("group.invite-expire-seconds", 60L));
		this.invites.put(target.getUniqueId(), new Invite(group, p.getUniqueId(), now + seconds * 1000L));
		this.msg().send(p, "group-invite-sent", Messages.tag("player", target.getName()));
		this.msg().send(target, "group-invite-received", Messages.tag("inviter", p.getName()), Messages.tag("seconds", seconds));
	}

	public void accept(Player p) {
		UUID uuid = p.getUniqueId();
		Invite invite = this.invites.remove(uuid);
		if (invite == null) {
			this.msg().send(p, "group-no-invite", new TagResolver[0]);
			return;
		}
		if (invite.expiresAt() < System.currentTimeMillis()) {
			this.msg().send(p, "group-invite-expired", new TagResolver[0]);
			return;
		}
		this.plugin.queue().leave(p, true);
		PartyGroup group = invite.group();
		if (this.groups.get(group.getLeader()) != group) {
			this.msg().send(p, "group-invite-gone", new TagResolver[0]);
			return;
		}
		if (this.groups.containsKey(uuid)) {
			this.msg().send(p, "group-already-in", new TagResolver[0]);
			return;
		}
		if (group.size() >= this.maxSize()) {
			this.msg().send(p, "group-full", Messages.tag("max", this.maxSize()));
			return;
		}
		this.broadcast(group, "group-member-joined", Messages.tag("player", p.getName()));
		group.add(uuid);
		this.groups.put(uuid, group);
		this.msg().send(p, "group-joined", Messages.tag("leader", this.nameOf(group.getLeader())));
		this.refresh(group);
	}

	public void deny(Player p) {
		Invite invite = this.invites.remove(p.getUniqueId());
		if (invite == null) {
			this.msg().send(p, "group-no-invite", new TagResolver[0]);
			return;
		}
		this.msg().send(p, "group-deny-done", new TagResolver[0]);
		Player inviter = Bukkit.getPlayer(invite.inviter());
		if (inviter != null) {
			this.msg().send(inviter, "group-denied", Messages.tag("player", p.getName()));
		}
	}

	public void leave(Player p) {
		PartyGroup group = this.groups.get(p.getUniqueId());
		if (group == null) {
			this.msg().send(p, "group-not-in", new TagResolver[0]);
			return;
		}
		this.detach(p, group, "group-member-left");
		this.msg().send(p, "group-left", new TagResolver[0]);
	}

	public void disband(Player p) {
		PartyGroup group = this.groups.get(p.getUniqueId());
		if (group == null) {
			this.msg().send(p, "group-not-in", new TagResolver[0]);
			return;
		}
		if (!group.isLeader(p.getUniqueId())) {
			this.msg().send(p, "group-not-leader", new TagResolver[0]);
			return;
		}
		this.plugin.manager().cancelMatch(group);
		for (UUID uuid : group.members()) {
			this.groups.remove(uuid);
			Player member = Bukkit.getPlayer(uuid);
			if (member == null) continue;
			this.clearBoard(member);
			this.plugin.manager().leaveQuietly(member);
			this.plugin.menu().removeSword(member);
			this.msg().send(member, "group-disbanded", new TagResolver[0]);
		}
		this.invites.values().removeIf(i -> i.group() == group);
	}

	public void remove(Player p, String targetName) {
		PartyGroup group = this.groups.get(p.getUniqueId());
		if (group == null) {
			this.msg().send(p, "group-not-in", new TagResolver[0]);
			return;
		}
		if (!group.isLeader(p.getUniqueId())) {
			this.msg().send(p, "group-not-leader", new TagResolver[0]);
			return;
		}
		Player target = Bukkit.getPlayerExact(targetName);
		if (target == null || !group.contains(target.getUniqueId())) {
			this.msg().send(p, "group-remove-not-member", Messages.tag("player", targetName));
			return;
		}
		if (target.getUniqueId().equals(p.getUniqueId())) {
			this.msg().send(p, "group-remove-self", new TagResolver[0]);
			return;
		}
		this.detach(target, group, "group-member-removed");
		this.msg().send(target, "group-removed", new TagResolver[0]);
	}

	public void list(Player p) {
		PartyGroup group = this.groups.get(p.getUniqueId());
		if (group == null) {
			this.msg().send(p, "group-not-in", new TagResolver[0]);
			return;
		}
		this.msg().send(p, "group-list-header", Messages.tag("count", group.size()), Messages.tag("max", this.maxSize()));
		String leaderTag = this.plugin.settings().messages().getString("placeholder.leader", "(leder)");
		for (UUID uuid : group.members()) {
			this.msg().send(p, "group-list-entry", Messages.tag("player", this.nameOf(uuid)), Messages.tag("role", group.isLeader(uuid) ? leaderTag : ""));
		}
	}

	public void handleQuit(Player p) {
		UUID uuid = p.getUniqueId();
		this.invites.remove(uuid);
		this.invites.values().removeIf(i -> i.inviter().equals(uuid));
		PartyGroup group = this.groups.get(uuid);
		if (group != null) {
			this.detach(p, group, "group-member-left");
		} else {
			this.boards.remove(uuid);
			this.previous.remove(uuid);
		}
	}

	public void refreshAll() {
		for (PartyGroup group : new HashSet<PartyGroup>(this.groups.values())) {
			this.refresh(group);
		}
	}

	public void shutdown() {
		for (UUID uuid : new ArrayList<UUID>(this.boards.keySet())) {
			Player p = Bukkit.getPlayer(uuid);
			if (p == null) continue;
			this.clearBoard(p);
		}
		this.boards.clear();
		this.previous.clear();
		this.groups.clear();
		this.invites.clear();
	}

	private void detach(Player p, PartyGroup group, String broadcastKey) {
		UUID uuid = p.getUniqueId();
		group.remove(uuid);
		this.groups.remove(uuid);
		this.plugin.menu().removeSword(p);
		this.clearBoard(p);
		if (group.isEmpty()) {
			this.invites.values().removeIf(i -> i.group() == group);
			return;
		}
		this.broadcast(group, broadcastKey, Messages.tag("player", p.getName()));
		if (group.isLeader(uuid)) {
			group.setLeader(group.first());
			Player newLeader = Bukkit.getPlayer(group.getLeader());
			if (newLeader != null) {
				this.plugin.menu().giveSword(newLeader);
			}
			this.broadcast(group, "group-leader-changed", Messages.tag("player", this.nameOf(group.getLeader())));
		}
		this.refresh(group);
	}

	private void broadcast(PartyGroup group, String key, TagResolver ... resolvers) {
		for (UUID uuid : group.members()) {
			Player member = Bukkit.getPlayer(uuid);
			if (member == null) continue;
			this.msg().send(member, key, resolvers);
		}
	}

	private void refresh(PartyGroup group) {
		for (UUID uuid : group.members()) {
			Player member = Bukkit.getPlayer(uuid);
			if (member == null || !member.isOnline()) continue;
			this.applyBoard(member, group);
		}
	}

	private void applyBoard(Player p, PartyGroup group) {
		if (!this.plugin.settings().config().getBoolean("group.scoreboard.enabled", true)) {
			this.clearBoard(p);
			return;
		}
		ScoreboardManager manager = Bukkit.getScoreboardManager();
		if (manager == null) {
			return;
		}
		UUID uuid = p.getUniqueId();
		Scoreboard board = this.boards.get(uuid);
		if (board == null) {
			this.previous.put(uuid, p.getScoreboard());
			board = manager.getNewScoreboard();
			this.boards.put(uuid, board);
		}
		this.render(p, board, group);
		if (p.getScoreboard() != board) {
			p.setScoreboard(board);
		}
	}

	private void render(Player p, Scoreboard board, PartyGroup group) {
		Objective old = board.getObjective("party");
		if (old != null) {
			old.unregister();
		}
		for (Team team : new ArrayList<Team>(board.getTeams())) {
			team.unregister();
		}
		TagResolver[] tags = new TagResolver[]{Messages.tag("count", group.size()), Messages.tag("max", this.maxSize()), Messages.tag("leader", this.nameOf(group.getLeader())), Messages.tag("player", p.getName())};
		Objective objective = board.registerNewObjective("party", Criteria.DUMMY, this.msg().component("scoreboard.title", tags));
		objective.numberFormat(NumberFormat.blank());
		objective.setDisplaySlot(DisplaySlot.SIDEBAR);
		String memberLine = this.plugin.settings().messages().getString("scoreboard.member-line", "<gray>- <white><player>");
		ArrayList<Component> lines = new ArrayList<Component>();
		for (String raw : this.plugin.settings().messages().getStringList("scoreboard.lines")) {
			if (raw.equals("{members}")) {
				for (UUID member : group.members()) {
					lines.add(this.msg().format(memberLine, Messages.tag("player", this.nameOf(member))));
				}
				continue;
			}
			lines.add(this.msg().format(raw, tags));
		}
		if (lines.size() > ENTRIES.length) {
			lines = new ArrayList(lines.subList(0, ENTRIES.length));
		}
		int score = lines.size();
		for (int i = 0; i < lines.size(); ++i) {
			Team team = board.registerNewTeam("line" + i);
			team.addEntry(ENTRIES[i]);
			team.prefix((Component)lines.get(i));
			objective.getScore(ENTRIES[i]).setScore(score--);
		}
	}

	private void clearBoard(Player p) {
		UUID uuid = p.getUniqueId();
		Scoreboard ours = this.boards.remove(uuid);
		Scoreboard before = this.previous.remove(uuid);
		if (ours == null || !p.isOnline() || p.getScoreboard() != ours) {
			return;
		}
		ScoreboardManager manager = Bukkit.getScoreboardManager();
		if (before != null) {
			p.setScoreboard(before);
		} else if (manager != null) {
			p.setScoreboard(manager.getMainScoreboard());
		}
	}

	private boolean onlyLeaderInvites() {
		return this.plugin.settings().config().getBoolean("group.only-leader-invites", true);
	}

	private String nameOf(UUID uuid) {
		Player online = Bukkit.getPlayer(uuid);
		if (online != null) {
			return online.getName();
		}
		String name = Bukkit.getOfflinePlayer(uuid).getName();
		return name != null ? name : "?";
	}

	private Messages msg() {
		return this.plugin.messages();
	}

	static {
		String hex = "0123456789abcdef";
		for (int i = 0; i < ENTRIES.length; ++i) {
			GroupManager.ENTRIES[i] = "\u00a7" + hex.charAt(i);
		}
	}

	private record Invite(PartyGroup group, UUID inviter, long expiresAt) {
	}
}