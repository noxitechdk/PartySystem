package dk.blockcraft.partysystem.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PartyGroup {
	private final CopyOnWriteArrayList<UUID> members = new CopyOnWriteArrayList();
	private volatile UUID leader;
	private volatile String kit;
	private volatile boolean teams;
	private volatile boolean dropItems;
	private volatile int rounds = 1;
	private final Map<UUID, Integer> teamMap = new ConcurrentHashMap<UUID, Integer>();

	public PartyGroup(UUID leader) {
		this.leader = leader;
		this.members.add(leader);
	}

	public UUID getLeader() {
		return this.leader;
	}

	public void setLeader(UUID leader) {
		this.leader = leader;
	}

	public boolean isLeader(UUID uuid) {
		return this.leader.equals(uuid);
	}

	public boolean contains(UUID uuid) {
		return this.members.contains(uuid);
	}

	public void add(UUID uuid) {
		this.members.addIfAbsent(uuid);
	}

	public void remove(UUID uuid) {
		this.members.remove(uuid);
	}

	public int size() {
		return this.members.size();
	}

	public boolean isEmpty() {
		return this.members.isEmpty();
	}

	public UUID first() {
		return this.members.get(0);
	}

	public List<UUID> members() {
		return List.copyOf(this.members);
	}

	public String getKit() {
		return this.kit;
	}

	public void setKit(String kit) {
		this.kit = kit;
	}

	public boolean isTeams() {
		return this.teams;
	}

	public void setTeams(boolean teams) {
		this.teams = teams;
	}

	public boolean isDropItems() {
		return this.dropItems;
	}

	public void setDropItems(boolean dropItems) {
		this.dropItems = dropItems;
	}

	public int getRounds() {
		return this.rounds;
	}

	public void setRounds(int rounds) {
		this.rounds = Math.max(1, rounds);
	}

	public int team(UUID uuid) {
		return this.teamMap.getOrDefault(uuid, 0);
	}

	public void setTeam(UUID uuid, int team) {
		this.teamMap.put(uuid, team == 1 ? 1 : 0);
	}

	public void balanceTeams() {
		this.teamMap.keySet().removeIf(id -> !this.members.contains(id));
		int[] count = new int[2];
		for (Integer t : this.teamMap.values()) {
			int n = t;
			count[n] = count[n] + 1;
		}
		for (UUID id2 : this.members) {
			if (this.teamMap.containsKey(id2)) continue;
			int t = count[0] <= count[1] ? 0 : 1;
			this.teamMap.put(id2, t);
			int n = t;
			count[n] = count[n] + 1;
		}
	}

	public void shuffleTeams() {
		ArrayList<UUID> list = new ArrayList<UUID>(this.members);
		Collections.shuffle(list);
		this.teamMap.clear();
		for (int i = 0; i < list.size(); ++i) {
			this.teamMap.put((UUID)list.get(i), i % 2);
		}
	}
}