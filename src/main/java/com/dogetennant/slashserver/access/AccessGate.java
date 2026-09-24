package com.dogetennant.slashserver.access;

import com.dogetennant.slashserver.SlashServer;
import com.dogetennant.slashserver.config.ConfigManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces per-server permissions on every route onto a server.
 *
 * The command classes check permissions too, but a command check only ever
 * guards its own command. Velocity's /server takes a single node
 * (velocity.command.server) for the whole network, so a player holding it can
 * reach any backend by name - a private server included. Plugins that move
 * players around bypass commands entirely.
 *
 * Checking at connection level covers all of it, because every route ends in a
 * {@link ServerPreConnectEvent}.
 */
public class AccessGate {

    /** How long a staff-granted pass stays usable before it lapses. */
    private static final long OVERRIDE_TTL_MILLIS = 5_000;

    private record Pass(String server, long expiresAt) {

        boolean covers(String target, long now) {
            return now < expiresAt && server.equalsIgnoreCase(target);
        }
    }

    private final SlashServer plugin;

    // One-shot passes issued by staff using the send command
    private final Map<UUID, Pass> passes = new ConcurrentHashMap<>();

    public AccessGate(SlashServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Lets one upcoming connection through for a player who has no node for the
     * destination, because staff with the override permission sent them there.
     *
     * Skipping the check in the command is not enough on its own: the connection
     * it starts still arrives here, where the player would be turned away. The
     * pass is single-use and short-lived so it authorises the send it was issued
     * for and nothing the player might try afterwards.
     */
    public void authoriseOnce(UUID player, String serverName) {
        passes.put(player, new Pass(serverName, System.currentTimeMillis() + OVERRIDE_TTL_MILLIS));
    }

    private boolean consumePass(UUID player, String serverName) {
        Pass pass = passes.remove(player);
        return pass != null && pass.covers(serverName, System.currentTimeMillis());
    }

    /**
     * Lists the node each server needs, so admins can see exactly what to grant
     * without having to derive it from the format string themselves.
     */
    public void logNodes() {
        ConfigManager config = plugin.getConfigManager();
        if (!config.isRequirePermission()) {
            return;
        }

        List<String> lines = new ArrayList<>();
        for (RegisteredServer server : plugin.getProxy().getAllServers()) {
            String name = server.getServerInfo().getName();
            lines.add(name + " -> " + config.permissionFor(name));
        }

        plugin.getLogger().info("Per-server permissions are ON. Nodes: {}. "
                        + "Holding {} allows every server.",
                String.join(", ", lines), config.getAccessBypassPermission());
    }

    /** Whether this player is allowed onto the named server. */
    public boolean mayJoin(CommandSource source, String serverName) {
        ConfigManager config = plugin.getConfigManager();
        if (!config.isRequirePermission()) {
            return true;
        }
        if (source.hasPermission(config.getAccessBypassPermission())) {
            return true;
        }
        return source.hasPermission(config.permissionFor(serverName));
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        ConfigManager config = plugin.getConfigManager();
        if (!config.isRequirePermission() || !config.isGuardAllConnections()) {
            return;
        }

        // Something else already refused this - most likely the login gate.
        // Leave its reason in place rather than stacking a second message.
        if (!event.getResult().isAllowed()) {
            return;
        }

        // Denying the first connection drops the player off the network entirely,
        // so it stays opt-in. It matters when a forced host points at a
        // restricted server, which is the one way a player picks where they land.
        if (event.getPreviousServer() == null && !config.isCheckInitialConnection()) {
            return;
        }

        // Another plugin may have redirected this already; guard where the player
        // is actually going, not where they originally asked to go
        RegisteredServer target = event.getResult().getServer().orElse(event.getOriginalServer());
        if (target == null) {
            return;
        }

        Player player = event.getPlayer();
        String name = target.getServerInfo().getName();
        if (mayJoin(player, name)) {
            return;
        }

        // Staff sent them here knowing they lack the node
        if (consumePass(player.getUniqueId(), name)) {
            plugin.getLogger().info("{} was sent to {} without holding {} - allowed by an override",
                    player.getUsername(), name, plugin.getConfigManager().permissionFor(name));
            return;
        }

        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        plugin.getLanguageManager().send(player, "no-permission",
                Placeholder.unparsed("server", name),
                Placeholder.parsed("display", name),
                Placeholder.unparsed("command", name),
                Placeholder.unparsed("permission", config.permissionFor(name)));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        passes.remove(event.getPlayer().getUniqueId());
    }
}
