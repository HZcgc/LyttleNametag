package com.lyttledev.lyttlenametag.formatting;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.platform.PlayerAdapter;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Renders a nametag line after resolving PlaceholderAPI values and native
 * LuckPerms components. LuckPerms metadata is parsed independently, so a
 * Birdflop MiniMessage gradient can be stored directly as the prefix.
 */
public final class NametagTextRenderer {
    private static final Pattern AMPERSAND_HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Pattern LEGACY_FORMATTING = Pattern.compile("(?i)[&§][0-9a-fk-or]");

    private final PlayerAdapter<Player> luckPermsPlayers;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Set<String> warnedTemplates = ConcurrentHashMap.newKeySet();

    public NametagTextRenderer(LuckPerms luckPerms) {
        this.luckPermsPlayers = luckPerms.getPlayerAdapter(Player.class);
    }

    public Component render(String template, Player player, Location location) {
        String input = normalizeBuiltInPlaceholders(template);
        input = PlaceholderAPI.setPlaceholders(player, input);

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("player", player.getName()))
                .resolver(Placeholder.component("displayname", player.displayName()))
                .resolver(Placeholder.unparsed("world", location.getWorld().getName()))
                .resolver(Placeholder.unparsed("x", Integer.toString(location.getBlockX())))
                .resolver(Placeholder.unparsed("y", Integer.toString(location.getBlockY())))
                .resolver(Placeholder.unparsed("z", Integer.toString(location.getBlockZ())))
                .resolver(Placeholder.unparsed("health", compactNumber(player.getHealth())))
                .resolver(Placeholder.unparsed("max_health", compactNumber(maxHealth(player))))
                .resolver(Placeholder.component("luckperms_prefix", luckPermsMeta(player, true)))
                .resolver(Placeholder.component("luckperms_suffix", luckPermsMeta(player, false)))
                .build();

        try {
            return miniMessage.deserialize(input, placeholders);
        } catch (RuntimeException exception) {
            if (warnedTemplates.add(template)) {
                player.getServer().getLogger().warning(
                        "[LyttleNametag] Invalid MiniMessage format in a nametag line: " + exception.getMessage()
                );
            }
            return Component.text(input);
        }
    }

    private String normalizeBuiltInPlaceholders(String input) {
        return input
                .replaceAll("(?i)%luckperms_prefix%", "<luckperms_prefix>")
                .replaceAll("(?i)%luckperms_suffix%", "<luckperms_suffix>")
                .replaceAll("(?i)%player_health%", "<health>")
                .replaceAll("(?i)%player_max_health%", "<max_health>")
                .replace("<PLAYER>", "<player>")
                .replace("<DISPLAYNAME>", "<displayname>")
                .replace("<WORLD>", "<world>")
                .replace("<X>", "<x>")
                .replace("<Y>", "<y>")
                .replace("<Z>", "<z>");
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0D : attribute.getValue();
    }

    private String compactNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private Component luckPermsMeta(Player player, boolean prefix) {
        CachedMetaData metaData = luckPermsPlayers.getMetaData(player);
        String value = prefix
                ? metaData.getPrefix()
                : metaData.getSuffix();
        return deserializeMeta(value);
    }

    private Component deserializeMeta(String value) {
        if (value == null || value.isEmpty()) {
            return Component.empty();
        }

        // Also keep existing Birdflop legacy-hex prefixes readable while native
        // MiniMessage (<gradient:...>) remains the preferred format.
        String normalized = AMPERSAND_HEX.matcher(value).replaceAll("<#$1>");
        if (!normalized.contains("<") && LEGACY_FORMATTING.matcher(normalized).find()) {
            LegacyComponentSerializer serializer = normalized.indexOf('§') >= 0
                    ? LegacyComponentSerializer.legacySection()
                    : LegacyComponentSerializer.legacyAmpersand();
            return serializer.deserialize(normalized);
        }

        try {
            return miniMessage.deserialize(normalized);
        } catch (RuntimeException ignored) {
            return Component.text(value);
        }
    }
}
