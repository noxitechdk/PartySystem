package dk.blockcraft.partysystem.data;

import dk.blockcraft.partysystem.PartySystem;
import dk.blockcraft.partysystem.data.Database;
import dk.blockcraft.partysystem.model.PartyPlayerData;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class DataManager {
	private final PartySystem plugin;
	private final Database database;
	private final Map<UUID, PartyPlayerData> cache = new ConcurrentHashMap<UUID, PartyPlayerData>();
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "PartySystem-IO");
		t.setDaemon(true);
		return t;
	});

	public DataManager(PartySystem plugin, Database database) {
		this.plugin = plugin;
		this.database = database;
	}

	public CompletableFuture<PartyPlayerData> load(UUID uuid) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				PartyPlayerData data = this.database.load(uuid);
				this.cache.put(uuid, data);
				return data;
			}
			catch (SQLException ex) {
				throw new CompletionException(ex);
			}
		}, this.io);
	}

	public CompletableFuture<PartyPlayerData> fetch(UUID uuid) {
		PartyPlayerData cached = this.cache.get(uuid);
		if (cached != null) {
			return CompletableFuture.completedFuture(cached.copy());
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				return this.database.load(uuid);
			}
			catch (SQLException ex) {
				throw new CompletionException(ex);
			}
		}, this.io);
	}

	public CompletableFuture<Void> update(UUID uuid, Consumer<PartyPlayerData> change) {
		PartyPlayerData cached = this.cache.get(uuid);
		if (cached != null) {
			change.accept(cached);
			this.save(uuid);
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.runAsync(() -> {
			try {
				PartyPlayerData data = this.database.load(uuid);
				change.accept(data);
				this.database.save(data);
			}
			catch (SQLException ex) {
				throw new CompletionException(ex);
			}
		}, this.io);
	}

	public Executor executor() {
		return this.io;
	}

	public Database database() {
		return this.database;
	}

	public PartyPlayerData get(UUID uuid) {
		return this.cache.get(uuid);
	}

	public boolean isLoaded(UUID uuid) {
		return this.cache.containsKey(uuid);
	}

	public void save(UUID uuid) {
		PartyPlayerData data = this.cache.get(uuid);
		if (data != null) {
			this.write(data.copy());
		}
	}

	public void unload(UUID uuid) {
		PartyPlayerData data = this.cache.remove(uuid);
		if (data != null) {
			this.write(data.copy());
		}
	}

	public void shutdown() {
		for (PartyPlayerData data : this.cache.values()) {
			this.write(data.copy());
		}
		this.cache.clear();
		this.io.shutdown();
		try {
			if (!this.io.awaitTermination(15L, TimeUnit.SECONDS)) {
				this.plugin.getLogger().warning("Gemning af party-data n\u00e5ede ikke at blive f\u00e6rdig.");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		this.database.close();
	}

	private void write(PartyPlayerData copy) {
		try {
			this.io.execute(() -> {
				try {
					this.database.save(copy);
				}
				catch (SQLException ex) {
					this.plugin.getLogger().log(Level.SEVERE, "Kunne ikke gemme data for " + String.valueOf(copy.getUuid()) + ": " + ex.getMessage(), ex);
				}
			});
		}
		catch (RejectedExecutionException ex) {
			this.plugin.getLogger().warning("Kunne ikke gemme data for " + String.valueOf(copy.getUuid()) + " (lukker ned).");
		}
	}
}