package dk.blockcraft.partysystem.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.bukkit.inventory.ItemStack;

public final class ItemCodec {
	private ItemCodec() {
	}

	public static byte[] encode(ItemStack[] items) {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();){
			byte[] byArray;
			try (DataOutputStream out = new DataOutputStream(bytes);){
				out.writeInt(items.length);
				for (ItemStack item : items) {
					if (item == null || item.getType().isAir()) {
						out.writeInt(0);
						continue;
					}
					byte[] b = item.serializeAsBytes();
					out.writeInt(b.length);
					out.write(b);
				}
				out.flush();
				byArray = bytes.toByteArray();
			}
			return byArray;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static ItemStack[] decode(byte[] data) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));){
			int size = in.readInt();
			if (size < 0 || size > 256) {
				throw new IOException("Ugyldig st\u00f8rrelse: " + size);
			}
			ItemStack[] items = new ItemStack[size];
			for (int i = 0; i < size; ++i) {
				int len = in.readInt();
				if (len <= 0) continue;
				byte[] b = new byte[len];
				in.readFully(b);
				items[i] = ItemStack.deserializeBytes(b);
			}
			return items;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}