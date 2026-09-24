package com.dogetennant.slashserver.config;

import com.dogetennant.slashserver.SlashServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads config.yml and turns the proxy server list into {@link ServerEntry} objects.
 *
 * The bundled config.yml is copied into the plugin folder on first run so admins
 * edit a fully commented file rather than an empty one.
 */
public class ConfigManager {

    private final SlashServer plugin;

    //  Settings
    private boolean autoMode;
    private Set<String> disabledServers = Set.of();
    private boolean overrideExisting;
    private boolean requirePermission;
    private String permissionFormat = "slashserver.server.<server>";
    private boolean hideWithoutPermission = true;
    private String accessBypassPermission = "slashserver.server.bypass";
    private boolean guardAllConnections = true;
    private boolean checkInitialConnection;
    private boolean sendOthers = true;
    private String sendOthersPermission = "slashserver.send.others";
    private String sendOverridePermission = "slashserver.send.override";
    private int cooldownSeconds;
    private String cooldownBypassPermission = "slashserver.cooldown.bypass";
    private boolean checkOnline;
    private int pingTimeoutMillis = 1000;
    private boolean reportConnectionFailures = true;
    private String requireLogin = "auto";
    private boolean guardAllSwitches = true;
    private String authChannel = "slashserver:auth";
    private long loginGraceMillis = 500;
    private Set<String> serversWithoutLogin = Set.of();

    // The raw servers: section, kept so entries can be rebuilt without a re-read
    private ConfigurationNode serversNode;

    public ConfigManager(SlashServer plugin) {
        this.plugin = plugin;
    }

    //
    // Loading
    //

    /**
     * Reads config.yml from the plugin folder, writing the bundled default first
     * if the file is missing.
     *
     * @return true when the file loaded; false leaves the previously loaded
     *         values in place so a broken edit never wipes a running config
     */
    public boolean load() {
        Path file = plugin.getDataDirectory().resolve("config.yml");
        if (!copyDefaultIfMissing(file, "config.yml")) {
            return false;
        }

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(file)
                .build();

        CommentedConfigurationNode root;
        try {
            root = loader.load();
        } catch (Exception e) {
            plugin.getLogger().error("Could not read config.yml - keeping the previously loaded settings", e);
            return false;
        }

        String mode = root.node("mode").getString("auto");
        autoMode = !mode.equalsIgnoreCase("manual");

        disabledServers = lowerCaseSet(getStringList(root.node("disabled-servers")));
        overrideExisting = root.node("override-existing").getBoolean(false);

        ConfigurationNode perms = root.node("permissions");
        requirePermission = perms.node("required").getBoolean(false);
        permissionFormat = perms.node("format").getString("slashserver.server.<server>");
        hideWithoutPermission = perms.node("hide-without-permission").getBoolean(true);
        accessBypassPermission = perms.node("bypass").getString("slashserver.server.bypass");
        guardAllConnections = perms.node("guard-all-connections").getBoolean(true);
        checkInitialConnection = perms.node("check-initial-connection").getBoolean(false);
        cooldownBypassPermission = perms.node("cooldown-bypass").getString("slashserver.cooldown.bypass");

        ConfigurationNode send = root.node("send-others");
        sendOthers = send.node("enabled").getBoolean(true);
        sendOthersPermission = send.node("permission").getString("slashserver.send.others");
        sendOverridePermission = send.node("override-permission").getString("slashserver.send.override");

        cooldownSeconds = root.node("cooldown-seconds").getInt(0);

        ConfigurationNode online = root.node("online-check");
        checkOnline = online.node("enabled").getBoolean(false);
        pingTimeoutMillis = Math.max(100, online.node("timeout-millis").getInt(1000));

        reportConnectionFailures = root.node("report-connection-failures").getBoolean(true);

        ConfigurationNode auth = root.node("authentication");
        requireLogin = auth.node("require-login").getString("auto");
        guardAllSwitches = auth.node("guard-all-switches").getBoolean(true);
        authChannel = auth.node("channel").getString("slashserver:auth");
        loginGraceMillis = Math.max(0, auth.node("login-grace-millis").getLong(500));
        serversWithoutLogin = lowerCaseSet(getStringList(auth.node("no-login-required")));

        serversNode = root.node("servers");
        return true;
    }

    //
    // Entry building
    //

    /**
     * Resolves which of the proxy servers get a command, and under which names.
     * Servers named in config.yml that the proxy does not know about are logged
     * and skipped - almost always a typo in the server name.
     */
    public List<ServerEntry> buildEntries(Collection<RegisteredServer> registered) {
        List<ServerEntry> entries = new ArrayList<>();
        Set<String> matchedConfigKeys = new HashSet<>();

        for (RegisteredServer server : registered) {
            String name = server.getServerInfo().getName();
            ConfigurationNode node = findServerNode(name);
            if (node != null) {
                matchedConfigKeys.add(String.valueOf(node.key()).toLowerCase(Locale.ROOT));
            }

            // manual mode only registers what the admin explicitly listed
            if (!autoMode && node == null) {
                continue;
            }
            if (disabledServers.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (node != null && !node.node("enabled").getBoolean(true)) {
                continue;
            }

            entries.add(toEntry(name, node));
        }

        warnAboutUnknownServers(matchedConfigKeys);
        return entries;
    }

    private ServerEntry toEntry(String serverName, ConfigurationNode node) {
        String command = serverName;
        List<String> aliases = List.of();
        String permission = null;
        String displayName = serverName;

        if (node != null) {
            command = node.node("command").getString(serverName);
            aliases = getStringList(node.node("aliases"));
            permission = node.node("permission").getString();
            displayName = node.node("display-name").getString(serverName);
        }

        if (permission == null || permission.isBlank()) {
            permission = permissionFor(serverName);
        }

        String primary = command.toLowerCase(Locale.ROOT).trim();

        // A duplicated alias would make Velocity register the same command twice
        List<String> cleanAliases = new ArrayList<>();
        for (String alias : aliases) {
            String lower = alias.toLowerCase(Locale.ROOT).trim();
            if (!lower.isEmpty() && !lower.equals(primary) && !cleanAliases.contains(lower)) {
                cleanAliases.add(lower);
            }
        }

        return new ServerEntry(serverName, primary, cleanAliases, permission, displayName);
    }

    /**
     * The permission node that lets a player onto this server.
     *
     * Keyed off the server name rather than a {@link ServerEntry}, because a
     * server with no slash command of its own still has to be guarded against
     * /server - which is the whole point of checking at connection level.
     */
    public String permissionFor(String serverName) {
        ConfigurationNode node = findServerNode(serverName);
        String override = node == null ? null : node.node("permission").getString();

        if (override != null && !override.isBlank()) {
            return override;
        }
        return permissionFormat.replace("<server>", serverName.toLowerCase(Locale.ROOT));
    }

    // Case-insensitive lookup so "Hub" in config.yml still matches server "hub"
    private ConfigurationNode findServerNode(String serverName) {
        if (serversNode == null || serversNode.virtual()) {
            return null;
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> child : serversNode.childrenMap().entrySet()) {
            if (String.valueOf(child.getKey()).equalsIgnoreCase(serverName)) {
                return child.getValue();
            }
        }
        return null;
    }

    private void warnAboutUnknownServers(Set<String> matchedConfigKeys) {
        if (serversNode == null || serversNode.virtual()) {
            return;
        }
        for (Object key : serversNode.childrenMap().keySet()) {
            if (!matchedConfigKeys.contains(String.valueOf(key).toLowerCase(Locale.ROOT))) {
                plugin.getLogger().warn("config.yml has a servers entry for \"{}\", but no server by that "
                        + "name is registered on the proxy - check velocity.toml", key);
            }
        }
    }

    //
    // Helpers
    //

    /**
     * Writes a bundled resource into the plugin folder when it is not there yet.
     *
     * @return false only when the file is missing and could not be created
     */
    public boolean copyDefaultIfMissing(Path target, String resourceName) {
        if (Files.exists(target)) {
            return true;
        }
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
                if (in == null) {
                    plugin.getLogger().error("Bundled {} is missing from the plugin jar", resourceName);
                    return false;
                }
                Files.copy(in, target);
            }
            plugin.getLogger().info("Created a default {}", resourceName);
            return true;
        } catch (IOException e) {
            plugin.getLogger().error("Could not write {}", resourceName, e);
            return false;
        }
    }

    private static List<String> getStringList(ConfigurationNode node) {
        try {
            List<String> list = node.getList(String.class);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Set<String> lowerCaseSet(List<String> values) {
        Set<String> set = new HashSet<>();
        for (String value : values) {
            set.add(value.toLowerCase(Locale.ROOT));
        }
        return set;
    }

    //
    // Getters
    //

    public boolean isAutoMode() {
        return autoMode;
    }

    public boolean isOverrideExisting() {
        return overrideExisting;
    }

    public boolean isRequirePermission() {
        return requirePermission;
    }

    public boolean isHideWithoutPermission() {
        return hideWithoutPermission;
    }

    /** Permission that allows a player onto every server. */
    public String getAccessBypassPermission() {
        return accessBypassPermission;
    }

    public boolean isGuardAllConnections() {
        return guardAllConnections;
    }

    public boolean isCheckInitialConnection() {
        return checkInitialConnection;
    }

    public boolean isSendOthers() {
        return sendOthers;
    }

    public String getSendOthersPermission() {
        return sendOthersPermission;
    }

    /** Lets staff send a player onto a server that player has no node for. */
    public String getSendOverridePermission() {
        return sendOverridePermission;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public String getCooldownBypassPermission() {
        return cooldownBypassPermission;
    }

    public boolean isCheckOnline() {
        return checkOnline;
    }

    public int getPingTimeoutMillis() {
        return pingTimeoutMillis;
    }

    public boolean isReportConnectionFailures() {
        return reportConnectionFailures;
    }

    /** Raw setting: "auto", "true" or "false" - resolved by the auth gate. */
    public String getRequireLogin() {
        return requireLogin;
    }

    public boolean isGuardAllSwitches() {
        return guardAllSwitches;
    }

    public String getAuthChannel() {
        return authChannel;
    }

    /** How long a switch waits for a login announcement before being refused. */
    public long getLoginGraceMillis() {
        return loginGraceMillis;
    }

    /** Lower-cased names of servers that have no login wall of their own. */
    public Set<String> getServersWithoutLogin() {
        return serversWithoutLogin;
    }
}
