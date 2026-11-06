package dk.blockcraft.partysystem.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record Point(String world, double x, double y, double z, float yaw, float pitch) {
	public static Point of(Location loc) {
		return new Point(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
	}

	public Location toLocation() {
		World w = Bukkit.getWorld(this.world);
		return w == null ? null : new Location(w, this.x, this.y, this.z, this.yaw, this.pitch);
	}
}