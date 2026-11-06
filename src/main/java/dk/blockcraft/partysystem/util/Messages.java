package dk.blockcraft.partysystem.util;

import dk.blockcraft.partysystem.util.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Messages {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private final Settings settings;

	public Messages(Settings settings) {
		this.settings = settings;
	}

	public static TagResolver tag(String name, Object value) {
		return Placeholder.unparsed((String)name, (String)String.valueOf(value));
	}

	public static TagResolver mini(String name, String value) {
		return Placeholder.parsed((String)name, (String)value);
	}

	public Component component(String key, TagResolver ... resolvers) {
		String raw = this.settings.messages().getString(key);
		return this.parse((String)(raw != null ? raw : "<red>Mangler besked: " + key), resolvers);
	}

	public void send(CommandSender to, String key, TagResolver ... resolvers) {
		to.sendMessage(this.component(key, resolvers));
	}

	public void sendList(CommandSender to, String key, TagResolver ... resolvers) {
		for (String line : this.settings.messages().getStringList(key)) {
			to.sendMessage(this.parse(line, resolvers));
		}
	}

	public void actionBar(Player to, String key, TagResolver ... resolvers) {
		to.sendActionBar(this.component(key, resolvers));
	}

	public Component format(String raw, TagResolver ... resolvers) {
		return this.parse(raw, resolvers);
	}

	public String plain(String key) {
		return PlainTextComponentSerializer.plainText().serialize(this.component(key, new TagResolver[0]));
	}

	private Component parse(String text, TagResolver ... resolvers) {
		TagResolver all = TagResolver.builder().resolvers(resolvers).resolver((TagResolver)Placeholder.parsed((String)"prefix", (String)this.settings.messages().getString("prefix", ""))).build();
		return MM.deserialize(text, all);
	}
}