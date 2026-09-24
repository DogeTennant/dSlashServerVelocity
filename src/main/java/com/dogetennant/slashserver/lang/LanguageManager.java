package com.dogetennant.slashserver.lang;

import com.dogetennant.slashserver.SlashServer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds every user-facing string from messages.yml.
 *
 * All text is MiniMessage, and every message can use {@code <prefix>} plus
 * whatever placeholders the caller passes in. Setting a message to an empty
 * string silences it - {@link #send} skips blank messages entirely rather than
 * sending an empty chat line.
 */
public class LanguageManager {

    private static final String FILE_NAME = "messages.yml";

    private final SlashServer plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Flattened "a.b.c" -> raw MiniMessage string
    private Map<String, String> messages = new HashMap<>();

    // Parsed once at load; every message gets it as a <prefix> tag
    private String rawPrefix = "";

    public LanguageManager(SlashServer plugin) {
        this.plugin = plugin;
    }

    //
    // Loading
    //

    /**
     * @return true when messages.yml loaded; false keeps the previous strings so
     *         a broken edit does not leave the plugin mute
     */
    public boolean load() {
        Path file = plugin.getDataDirectory().resolve(FILE_NAME);
        if (!plugin.getConfigManager().copyDefaultIfMissing(file, FILE_NAME)) {
            return false;
        }

        CommentedConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).build().load();
        } catch (Exception e) {
            plugin.getLogger().error("Could not read {} - keeping the previously loaded messages", FILE_NAME, e);
            return false;
        }

        Map<String, String> loaded = new HashMap<>();
        flatten(root, "", loaded);

        messages = loaded;
        rawPrefix = loaded.getOrDefault("prefix", "");
        return true;
    }

    private void flatten(ConfigurationNode node, String path, Map<String, String> out) {
        if (node.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> child : node.childrenMap().entrySet()) {
                String key = String.valueOf(child.getKey());
                flatten(child.getValue(), path.isEmpty() ? key : path + "." + key, out);
            }
            return;
        }

        String value = node.getString();
        if (value != null && !path.isEmpty()) {
            out.put(path, value);
        }
    }

    //
    // Lookup
    //

    /**
     * Parses a message into a component.
     *
     * A missing key renders as the key itself in red so a typo is obvious in
     * game rather than silently producing an empty line.
     */
    public Component get(String key, TagResolver... resolvers) {
        String raw = messages.get(key);
        if (raw == null) {
            return Component.text("missing message: " + key, NamedTextColor.RED);
        }

        TagResolver[] all = new TagResolver[resolvers.length + 1];
        all[0] = Placeholder.parsed("prefix", rawPrefix);
        System.arraycopy(resolvers, 0, all, 1, resolvers.length);

        return miniMessage.deserialize(raw, all);
    }

    /** Sends a message, skipping it entirely when it is blank or missing. */
    public void send(Audience target, String key, TagResolver... resolvers) {
        if (isSilent(key)) {
            return;
        }
        target.sendMessage(get(key, resolvers));
    }

    /**
     * True when the message was deliberately set to an empty string.
     *
     * A key that is missing outright is not silent - it still renders the red
     * fallback, so a line dropped during an upgrade shows up instead of leaving
     * players with no feedback at all.
     */
    public boolean isSilent(String key) {
        String raw = messages.get(key);
        return raw != null && raw.isBlank();
    }
}
