package com.dogetennant.slashserver.command;

import com.dogetennant.slashserver.SlashServer;
import com.dogetennant.slashserver.config.ConfigManager;
import com.dogetennant.slashserver.config.ServerEntry;
import com.dogetennant.slashserver.lang.LanguageManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The command registered for a single server - {@code /hub}, {@code /survival},
 * and so on.
 *
 * One instance is created per {@link ServerEntry}; the entry is resolved at
 * registration time so execution never touches the config.
 *
 * <pre>
 *   /hub            connect yourself
 *   /hub &lt;player&gt;    send someone else (needs the send-others permission)
 * </pre>
 */
public class ServerCommand implements SimpleCommand {

    private final SlashServer plugin;
    private final ServerEntry entry;

    public ServerCommand(SlashServer plugin, ServerEntry entry) {
        this.plugin = plugin;
        this.entry = entry;
    }

    //
    // Execution
    //

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        LanguageManager lang = plugin.getLanguageManager();

        // Messages quote the alias that was actually typed, so a player who ran
        // /lobby is not told to use /hub
        TagResolver tags = serverTags(invocation.alias());

        // The server can disappear from under us if another plugin unregisters
        // it after our command was built
        Optional<RegisteredServer> resolved = plugin.getProxy().getServer(entry.serverName());
        if (resolved.isEmpty()) {
            lang.send(source, "server-unknown", tags);
            return;
        }
        RegisteredServer server = resolved.get();

        if (args.length > 0) {
            sendOther(source, server, args, tags);
            return;
        }

        if (!(source instanceof Player player)) {
            lang.send(source, "player-only", tags);
            return;
        }

        // Checked before anything else a player could learn from: someone stuck at
        // the login wall should not be able to probe which servers exist
        if (!plugin.getAuthGate().maySwitch(player)) {
            lang.send(player, "not-authenticated", tags);
            return;
        }

        ConfigManager config = plugin.getConfigManager();
        if (config.isRequirePermission() && !player.hasPermission(entry.permission())) {
            lang.send(player, "no-permission",
                    tags, Placeholder.unparsed("permission", entry.permission()));
            return;
        }

        if (isConnectedTo(player, server)) {
            lang.send(player, "already-connected", tags);
            return;
        }

        long wait = plugin.getCooldownManager().remaining(player.getUniqueId(), config.getCooldownSeconds());
        if (wait > 0 && !player.hasPermission(config.getCooldownBypassPermission())) {
            lang.send(player, "cooldown", tags, Placeholder.unparsed("time", String.valueOf(wait)));
            return;
        }

        lang.send(player, "connecting", tags);
        attemptConnect(player, server, player, false, tags);
    }

    /**
     * {@code /hub <player>} - move somebody else.
     *
     * The sender needs the send-others node, not the destination's own node:
     * staff routinely move players onto servers they have no reason to be on
     * themselves. The player being moved still needs it, because the per-server
     * permission governs who may be on a server, not who may put them there.
     */
    private void sendOther(CommandSource source, RegisteredServer server, String[] args, TagResolver tags) {
        LanguageManager lang = plugin.getLanguageManager();
        ConfigManager config = plugin.getConfigManager();

        if (!config.isSendOthers() || args.length > 1) {
            lang.send(source, "usage", tags);
            return;
        }

        if (!source.hasPermission(config.getSendOthersPermission())) {
            lang.send(source, "no-permission",
                    tags, Placeholder.unparsed("permission", config.getSendOthersPermission()));
            return;
        }

        // Permissions can be held before logging in, so holding the send node is
        // not on its own proof of who is at the keyboard
        if (source instanceof Player self && !plugin.getAuthGate().maySwitch(self)) {
            lang.send(source, "not-authenticated", tags);
            return;
        }

        Optional<Player> found = plugin.getProxy().getPlayer(args[0]);
        if (found.isEmpty()) {
            lang.send(source, "player-not-found", tags, Placeholder.unparsed("player", args[0]));
            return;
        }
        Player target = found.get();

        // The per-server permission says where a player may be, not who may push
        // them there, so the target needs it even though the sender is the one
        // running the command. Staff holding the override node can place someone
        // on a server they are barred from - moving a player to a private server
        // to help them is a normal thing to need to do.
        boolean overriding = false;
        if (!plugin.getAccessGate().mayJoin(target, entry.serverName())) {
            if (!source.hasPermission(config.getSendOverridePermission())) {
                lang.send(source, "target-no-permission",
                        tags, Placeholder.unparsed("player", target.getUsername()));
                return;
            }
            overriding = true;
        }

        // Moving an unauthenticated player past the login wall is the same bypass,
        // whoever pressed the button
        if (!plugin.getAuthGate().maySwitch(target)) {
            lang.send(source, "target-not-authenticated",
                    tags, Placeholder.unparsed("player", target.getUsername()));
            return;
        }

        if (isConnectedTo(target, server)) {
            lang.send(source, "target-already-connected",
                    tags, Placeholder.unparsed("player", target.getUsername()));
            return;
        }

        lang.send(source, "sent-other", tags, Placeholder.unparsed("player", target.getUsername()));
        lang.send(target, "sent-by-other", tags, Placeholder.unparsed("sender", sourceName(source)));

        // Issued only for the connection about to be made; the gate would refuse
        // it otherwise, since the target still has no node for this server
        if (overriding) {
            plugin.getAccessGate().authoriseOnce(target.getUniqueId(), entry.serverName());
        }

        attemptConnect(target, server, source, true, tags);
    }

    //
    // Connecting
    //

    /**
     * Optionally pings the backend first, then hands the player over.
     *
     * The ping is off by default: it costs a round trip on every command, but it
     * turns Velocity's generic connection error into a message an admin controls.
     */
    private void attemptConnect(Player player, RegisteredServer server, CommandSource initiator,
                                boolean byOther, TagResolver tags) {
        ConfigManager config = plugin.getConfigManager();

        if (!config.isCheckOnline()) {
            connect(player, server, initiator, byOther, tags);
            return;
        }

        server.ping()
                .orTimeout(config.getPingTimeoutMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ping, error) -> {
                    if (error != null) {
                        plugin.getLanguageManager().send(initiator, "server-offline", tags);
                        return;
                    }
                    connect(player, server, initiator, byOther, tags);
                });
    }

    private void connect(Player player, RegisteredServer server, CommandSource initiator,
                         boolean byOther, TagResolver tags) {
        player.createConnectionRequest(server).connect().whenComplete((result, error) -> {
            if (error != null) {
                plugin.getLogger().warn("Connecting {} to {} failed",
                        player.getUsername(), entry.serverName(), error);
                reportFailure(initiator, null, tags);
                return;
            }

            if (!result.isSuccessful()) {
                reportFailure(initiator, result.getReasonComponent().orElse(null), tags);
                return;
            }

            // Only a player switching themselves starts a cooldown; being sent by
            // an admin should not lock the player out of their own next switch
            if (!byOther) {
                plugin.getCooldownManager().markUsed(player.getUniqueId());
            }
            plugin.getLanguageManager().send(player, "connected", tags);
        });
    }

    private void reportFailure(CommandSource initiator, Component reason, TagResolver tags) {
        if (!plugin.getConfigManager().isReportConnectionFailures()) {
            return;
        }
        plugin.getLanguageManager().send(initiator, "connect-failed", tags,
                Placeholder.component("reason", reason == null ? Component.empty() : reason));
    }

    //
    // Suggestions and visibility
    //

    @Override
    public List<String> suggest(Invocation invocation) {
        ConfigManager config = plugin.getConfigManager();
        String[] args = invocation.arguments();

        if (!config.isSendOthers() || args.length > 1) {
            return List.of();
        }
        if (!invocation.source().hasPermission(config.getSendOthersPermission())) {
            return List.of();
        }

        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : plugin.getProxy().getAllPlayers()) {
            if (player.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(player.getUsername());
            }
        }
        return names;
    }

    /**
     * Controls whether the command shows up at all.
     *
     * With hide-without-permission off the command stays visible and execution
     * replies with the no-permission message, which is what most networks want
     * so players are told the server exists but is closed to them.
     */
    @Override
    public boolean hasPermission(Invocation invocation) {
        ConfigManager config = plugin.getConfigManager();
        if (!config.isRequirePermission() || !config.isHideWithoutPermission()) {
            return true;
        }
        return invocation.source().hasPermission(entry.permission());
    }

    //
    // Helpers
    //

    private boolean isConnectedTo(Player player, RegisteredServer server) {
        Optional<ServerConnection> current = player.getCurrentServer();
        return current.isPresent()
                && current.get().getServerInfo().getName().equals(server.getServerInfo().getName());
    }

    private static String sourceName(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : "Console";
    }

    /** The placeholders every message for this server gets. */
    private TagResolver serverTags(String alias) {
        return TagResolver.resolver(
                Placeholder.unparsed("server", entry.serverName()),
                Placeholder.parsed("display", entry.displayName()),
                Placeholder.unparsed("command", alias == null ? entry.command() : alias));
    }
}
