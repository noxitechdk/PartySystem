package dk.blockcraft.partysystem.model;

import java.util.UUID;

public record PartyStats(UUID uuid, String name, int elo, int peakElo, int wins, int losses, int draws, int streak, int bestStreak, int kills, int deaths) {
	public static PartyStats fresh(UUID uuid, String name, int startElo) {
		return new PartyStats(uuid, name == null ? "" : name, startElo, startElo, 0, 0, 0, 0, 0, 0, 0);
	}

	public PartyStats withName(String newName) {
		if (newName == null || newName.isBlank() || newName.equals(this.name)) {
			return this;
		}
		return new PartyStats(this.uuid, newName, this.elo, this.peakElo, this.wins, this.losses, this.draws, this.streak, this.bestStreak, this.kills, this.deaths);
	}

	public int matches() {
		return this.wins + this.losses + this.draws;
	}

	public int winRate() {
		int total = this.matches();
		return total == 0 ? 0 : Math.round((float)this.wins * 100.0f / (float)total);
	}

	public double kdr() {
		return this.deaths == 0 ? (double)this.kills : (double)this.kills / (double)this.deaths;
	}
}