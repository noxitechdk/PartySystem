package dk.blockcraft.partysystem.data;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.model.PartyPlayerData;
import dk.blockcraft.partysystem.model.PartyStats;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Database {
	private final Connection connection;
	private static final String STATS_COLUMNS = "uuid, name, elo, peak_elo, wins, losses, draws, streak, best_streak, kills, deaths";

	public Database(PartySystem plugin) throws SQLException {
		File folder = plugin.getDataFolder();
		if (!folder.exists() && !folder.mkdirs()) {
			throw new SQLException("Kunne ikke oprette mappen " + String.valueOf(folder));
		}
		try {
			Class.forName("org.sqlite.JDBC");
		}
		catch (ClassNotFoundException ex) {
			throw new SQLException("SQLite-driveren blev ikke fundet", ex);
		}
		this.connection = DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "data.db").getAbsolutePath());
		try (Statement st = this.connection.createStatement();){
			st.execute("PRAGMA journal_mode=WAL");
			st.execute("PRAGMA synchronous=NORMAL");
			st.execute("PRAGMA busy_timeout=5000");
			st.executeUpdate("CREATE TABLE IF NOT EXISTS party_players (uuid TEXT PRIMARY KEY, kit BLOB, last_save INTEGER NOT NULL DEFAULT 0, restocks INTEGER NOT NULL DEFAULT 0, joins INTEGER NOT NULL DEFAULT 0, original TEXT)");
			st.executeUpdate("CREATE TABLE IF NOT EXISTS party_stats (uuid TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', elo INTEGER NOT NULL, peak_elo INTEGER NOT NULL, wins INTEGER NOT NULL DEFAULT 0, losses INTEGER NOT NULL DEFAULT 0, draws INTEGER NOT NULL DEFAULT 0, streak INTEGER NOT NULL DEFAULT 0, best_streak INTEGER NOT NULL DEFAULT 0, kills INTEGER NOT NULL DEFAULT 0, deaths INTEGER NOT NULL DEFAULT 0)");
			st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_party_stats_elo ON party_stats(elo DESC)");
		}
	}

	public synchronized PartyPlayerData load(UUID uuid) throws SQLException {
		try (PreparedStatement ps = this.connection.prepareStatement("SELECT kit, last_save, restocks, joins, original FROM party_players WHERE uuid = ?");){
			PartyPlayerData partyPlayerData;
			block16: {
				ResultSet rs;
				block14: {
					PartyPlayerData partyPlayerData2;
					block15: {
						ps.setString(1, uuid.toString());
						rs = ps.executeQuery();
						try {
							if (rs.next()) break block14;
							partyPlayerData2 = new PartyPlayerData(uuid);
							if (rs == null) break block15;
						}
						catch (Throwable throwable) {
							if (rs != null) {
								try {
									rs.close();
								}
								catch (Throwable throwable2) {
									throwable.addSuppressed(throwable2);
								}
							}
							throw throwable;
						}
						rs.close();
					}
					return partyPlayerData2;
				}
				partyPlayerData = new PartyPlayerData(uuid, rs.getBytes("kit"), rs.getLong("last_save"), rs.getInt("restocks"), rs.getInt("joins"), rs.getString("original"));
				if (rs == null) break block16;
				rs.close();
			}
			return partyPlayerData;
		}
	}

	public synchronized void save(PartyPlayerData data) throws SQLException {
		try (PreparedStatement ps = this.connection.prepareStatement("INSERT INTO party_players (uuid, kit, last_save, restocks, joins, original) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET kit = excluded.kit, last_save = excluded.last_save, restocks = excluded.restocks, joins = excluded.joins, original = excluded.original");){
			ps.setString(1, data.getUuid().toString());
			byte[] kit = data.getKit();
			if (kit == null) {
				ps.setNull(2, 2004);
			} else {
				ps.setBytes(2, kit);
			}
			ps.setLong(3, data.getLastSave());
			ps.setInt(4, data.getRestocks());
			ps.setInt(5, data.getJoins());
			String original = data.getOriginal();
			if (original == null) {
				ps.setNull(6, 12);
			} else {
				ps.setString(6, original);
			}
			ps.executeUpdate();
		}
	}

	private static PartyStats readStats(ResultSet rs) throws SQLException {
		return new PartyStats(UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getInt("elo"), rs.getInt("peak_elo"), rs.getInt("wins"), rs.getInt("losses"), rs.getInt("draws"), rs.getInt("streak"), rs.getInt("best_streak"), rs.getInt("kills"), rs.getInt("deaths"));
	}

	public synchronized PartyStats loadStats(UUID uuid) throws SQLException {
		try (PreparedStatement ps = this.connection.prepareStatement("SELECT uuid, name, elo, peak_elo, wins, losses, draws, streak, best_streak, kills, deaths FROM party_stats WHERE uuid = ?");){
			PartyStats partyStats;
			block12: {
				ps.setString(1, uuid.toString());
				ResultSet rs = ps.executeQuery();
				try {
					PartyStats partyStats2 = partyStats = rs.next() ? Database.readStats(rs) : null;
					if (rs == null) break block12;
				}
				catch (Throwable throwable) {
					if (rs != null) {
						try {
							rs.close();
						}
						catch (Throwable throwable2) {
							throwable.addSuppressed(throwable2);
						}
					}
					throw throwable;
				}
				rs.close();
			}
			return partyStats;
		}
	}

	public synchronized PartyStats findStatsByName(String name) throws SQLException {
		try (PreparedStatement ps = this.connection.prepareStatement("SELECT uuid, name, elo, peak_elo, wins, losses, draws, streak, best_streak, kills, deaths FROM party_stats WHERE name = ? COLLATE NOCASE ORDER BY elo DESC LIMIT 1");){
			PartyStats partyStats;
			block12: {
				ps.setString(1, name);
				ResultSet rs = ps.executeQuery();
				try {
					PartyStats partyStats2 = partyStats = rs.next() ? Database.readStats(rs) : null;
					if (rs == null) break block12;
				}
				catch (Throwable throwable) {
					if (rs != null) {
						try {
							rs.close();
						}
						catch (Throwable throwable2) {
							throwable.addSuppressed(throwable2);
						}
					}
					throw throwable;
				}
				rs.close();
			}
			return partyStats;
		}
	}

	public synchronized void saveStats(PartyStats s) throws SQLException {
		try (PreparedStatement ps = this.connection.prepareStatement("INSERT INTO party_stats (uuid, name, elo, peak_elo, wins, losses, draws, streak, best_streak, kills, deaths) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, elo = excluded.elo, peak_elo = excluded.peak_elo, wins = excluded.wins, losses = excluded.losses, draws = excluded.draws, streak = excluded.streak, best_streak = excluded.best_streak, kills = excluded.kills, deaths = excluded.deaths");){
			ps.setString(1, s.uuid().toString());
			ps.setString(2, s.name() == null ? "" : s.name());
			ps.setInt(3, s.elo());
			ps.setInt(4, s.peakElo());
			ps.setInt(5, s.wins());
			ps.setInt(6, s.losses());
			ps.setInt(7, s.draws());
			ps.setInt(8, s.streak());
			ps.setInt(9, s.bestStreak());
			ps.setInt(10, s.kills());
			ps.setInt(11, s.deaths());
			ps.executeUpdate();
		}
	}

	public synchronized void deleteStats(UUID uuid) throws SQLException {
		try (PreparedStatement ps = this.connection.prepareStatement("DELETE FROM party_stats WHERE uuid = ?");){
			ps.setString(1, uuid.toString());
			ps.executeUpdate();
		}
	}

	public synchronized int rankOf(int elo) throws SQLException {
		try (PreparedStatement ps = this.connection.prepareStatement("SELECT COUNT(*) FROM party_stats WHERE elo > ? AND (wins + losses + draws) > 0");){
			int n;
			block12: {
				ps.setInt(1, elo);
				ResultSet rs = ps.executeQuery();
				try {
					int n2 = n = rs.next() ? rs.getInt(1) + 1 : 1;
					if (rs == null) break block12;
				}
				catch (Throwable throwable) {
					if (rs != null) {
						try {
							rs.close();
						}
						catch (Throwable throwable2) {
							throwable.addSuppressed(throwable2);
						}
					}
					throw throwable;
				}
				rs.close();
			}
			return n;
		}
	}

	public synchronized List<PartyStats> top(String column, int limit) throws SQLException {
		String col = switch (column) {
			case "wins" -> "wins";
			case "best_streak" -> "best_streak";
			case "kills" -> "kills";
			default -> "elo";
		};
		String where = "(wins + losses + draws) > 0" + (String)(col.equals("elo") ? "" : " AND " + col + " > 0");
		ArrayList<PartyStats> out = new ArrayList<PartyStats>();
		try (PreparedStatement ps = this.connection.prepareStatement("SELECT uuid, name, elo, peak_elo, wins, losses, draws, streak, best_streak, kills, deaths FROM party_stats WHERE " + where + " ORDER BY " + col + " DESC, elo DESC, wins DESC LIMIT ?");){
			ps.setInt(1, limit);
			try (ResultSet rs = ps.executeQuery();){
				while (rs.next()) {
					out.add(Database.readStats(rs));
				}
			}
		}
		return out;
	}

	public synchronized void close() {
		try {
			this.connection.close();
		}
		catch (SQLException sQLException) {
		}
	}
}