package dk.blockcraft.partysystem.model;

import dk.blockcraft.partysystem.util.ItemCodec;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public final class PartyPlayerData {
	private final UUID uuid;
	private volatile byte[] kit;
	private volatile long lastSave;
	private volatile int restocks;
	private volatile int joins;
	private volatile String original;

	public PartyPlayerData(UUID uuid) {
		this.uuid = uuid;
	}

	public PartyPlayerData(UUID uuid, byte[] kit, long lastSave, int restocks, int joins, String original) {
		this.uuid = uuid;
		this.kit = kit;
		this.lastSave = lastSave;
		this.restocks = restocks;
		this.joins = joins;
		this.original = original;
	}

	public PartyPlayerData copy() {
		return new PartyPlayerData(this.uuid, this.kit, this.lastSave, this.restocks, this.joins, this.original);
	}

	public UUID getUuid() {
		return this.uuid;
	}

	public byte[] getKit() {
		return this.kit;
	}

	public boolean hasKit() {
		return this.kit != null && this.kit.length > 0;
	}

	public ItemStack[] kitItems() {
		return this.hasKit() ? ItemCodec.decode(this.kit) : null;
	}

	public void setKitItems(ItemStack[] items) {
		this.kit = items == null ? null : ItemCodec.encode(items);
	}

	public long getLastSave() {
		return this.lastSave;
	}

	public void setLastSave(long lastSave) {
		this.lastSave = lastSave;
	}

	public int getRestocks() {
		return this.restocks;
	}

	public void setRestocks(int restocks) {
		this.restocks = restocks;
	}

	public int getJoins() {
		return this.joins;
	}

	public void setJoins(int joins) {
		this.joins = joins;
	}

	public String getOriginal() {
		return this.original;
	}

	public void setOriginal(String original) {
		this.original = original;
	}
}