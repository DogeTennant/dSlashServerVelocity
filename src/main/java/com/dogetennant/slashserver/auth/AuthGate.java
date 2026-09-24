package com.dogetennant.slashserver.auth;

import com.dogetennant.slashserver.SlashServer;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.google.common.io.ByteArrayDataInput;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Refuses to move players who have not logged in.
 *
 * Server commands are registered on the proxy, so Velocity matches and runs them
 * itself and never forwards them to the backend. A login wall running on the
 * backend therefore has nothing to cancel: without this gate, /survival typed at
 * the login screen is a straight bypass. The same is true of Velocity's own
 * /server, which is why {@link #onServerPreConnect} guards every switch on the
 * proxy rather than only the commands this plugin registers.
 *
 * The proxy has no way of knowing on its own whether a player authenticated -
 * that state lives in the auth plugin on the backend. A small companion plugin
 * on each backend announces logins over a plugin message channel (see
 * backend-bridge/ in this repository); everything here keys off that.
 *
 * Fails closed: a player the proxy has never heard about cannot switch servers.
 */
public class AuthGate {

    /** Payload verbs sent by the backend bridge. */
    private static final String LOGIN = "LOGIN";
    private static final String LOGOUT = "LOGOUT";

    private final SlashServer plugin;

    // The server each player is currently logged in ON, as reported by that
    // server. Single-valued on purpose: arriving somewhere new leaves the old
    // entry stale, which is exactly when the player must authenticate again.
    private final Map<UUID, String> authenticatedOn = new ConcurrentHashMap<>();

    // Switches parked while we wait to hear whether the player just logged in
    private final Map<UUID, CompletableFuture<Void>> pendingLogins = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile MinecraftChannelIdentifier channel;

    // Logged once per session so a missing bridge is obvious but not spammy
    private volatile boolean warnedAboutSilentBridge;

    public AuthGate(SlashServer plugin) {
        this.plugin = plugin;
    }

    //
    // Lifecycle
    //

    /** Resolves the enabled state and (re)registers the plugin message channel. */
    public void initialise() {
        String mode = plugin.getConfigManager().getRequireLogin().toLowerCase(Locale.ROOT);
        boolean onlineMode = plugin.getProxy().getConfiguration().isOnlineMode();

        // "auto" protects exactly the networks that need it: an offline-mode proxy
        // is the only kind that has a password wall to bypass in the first place
        enabled = switch (mode) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> !onlineMode;
        };

        MinecraftChannelIdentifier newChannel = parseChannel(plugin.getConfigManager().getAuthChannel());
        if (newChannel != null && !newChannel.equals(channel)) {
            if (channel != null) {
                plugin.getProxy().getChannelRegistrar().unregister(channel);
            }
            plugin.getProxy().getChannelRegistrar().register(newChannel);
            channel = newChannel;
        }

        if (enabled) {
            plugin.getLogger().info("Login gate is ON ({}): players must authenticate before "
                            + "switching servers. Listening for logins on \"{}\".",
                    mode.equals("auto") ? "auto - proxy is in offline mode" : "require-login: " + mode,
                    channel == null ? "<invalid channel>" : channel.getId());
        } else if (!onlineMode) {
            plugin.getLogger().warn("Login gate is OFF while the proxy is in OFFLINE mode. Server "
                    + "commands can be used to walk past a backend login wall. Set "
                    + "authentication.require-login to auto or true in config.yml.");
        }
    }

    private MinecraftChannelIdentifier parseChannel(String raw) {
        try {
            return MinecraftChannelIdentifier.from(raw);
        } catch (Exception e) {
            plugin.getLogger().error("authentication.channel \"{}\" is not a valid channel "
                    + "(expected namespace:name) - the login gate cannot receive logins", raw);
            return null;
        }
    }

    //
    // State
    //

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether this player may be moved off the server they are on.
     *
     * Authentication is tracked per server, not per proxy session. AuthPlayers
     * keeps its login state in a per-server list in the backend JVM, so passing
     * the wall on the lobby says nothing about the wall on survival. Remembering
     * a single "logged in at least once" flag let a player log in once and then
     * roam the whole network while sitting at an unpassed wall on every backend
     * they landed on - the original hole, one login later.
     *
     * A player auto-admitted by the backend's own session resume is not stranded
     * by this: no login event fires for them, but the bridge's poll sees the
     * state flip and announces it.
     */
    public boolean maySwitch(Player player) {
        if (!enabled) {
            return true;
        }

        // Not on a backend yet - this is the initial join, there is no wall to pass
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isEmpty()) {
            return true;
        }

        String server = current.get().getServerInfo().getName();
        if (plugin.getConfigManager().getServersWithoutLogin().contains(server.toLowerCase(Locale.ROOT))) {
            return true;
        }

        return server.equals(authenticatedOn.get(player.getUniqueId()));
    }

    public void markAuthenticated(UUID player, String server) {
        authenticatedOn.put(player, server);

        // Releases any switch parked in onServerPreConnect waiting for this
        CompletableFuture<Void> waiting = pendingLogins.remove(player);
        if (waiting != null) {
            waiting.complete(null);
        }
    }

    /** Only clears the record if the player is still logged in on that server. */
    public void clearOn(UUID player, String server) {
        authenticatedOn.remove(player, server);
    }

    private CompletableFuture<Void> awaitLogin(UUID player) {
        return pendingLogins.computeIfAbsent(player, key -> new CompletableFuture<>());
    }

    public int trackedCount() {
        return authenticatedOn.size();
    }

    //
    // Events
    //

    /**
     * Receives login announcements from the backend bridge.
     *
     * Only messages arriving from a backend server are trusted. A modified client
     * can send plugin messages on any registered channel, so accepting one that
     * originated from a Player would hand every client a self-service bypass -
     * the exact hole this class exists to close.
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (channel == null || !event.getIdentifier().equals(channel)) {
            return;
        }

        // Never let this reach another server, whoever sent it
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection backend)) {
            plugin.getLogger().warn("Ignoring a login message on {} that did not come from a backend "
                    + "server - a client tried to authenticate itself", channel.getId());
            return;
        }

        String action;
        UUID subject;
        try {
            ByteArrayDataInput in = event.dataAsDataStream();
            action = in.readUTF();
            subject = UUID.fromString(in.readUTF());
        } catch (Exception e) {
            plugin.getLogger().warn("Malformed login message from {}",
                    backend.getServerInfo().getName(), e);
            return;
        }

        // The bridge sends over the connection of the player it is talking about,
        // so anything else is a backend trying to authenticate a third party
        UUID sender = backend.getPlayer().getUniqueId();
        if (!sender.equals(subject)) {
            plugin.getLogger().warn("Server {} sent a login message for {} over {}'s connection - ignored",
                    backend.getServerInfo().getName(), subject, sender);
            return;
        }

        switch (action.toUpperCase(Locale.ROOT)) {
            case LOGIN -> {
                markAuthenticated(subject, backend.getServerInfo().getName());
                plugin.getLogger().debug("{} authenticated on {}",
                        backend.getPlayer().getUsername(), backend.getServerInfo().getName());
            }
            case LOGOUT -> clearOn(subject, backend.getServerInfo().getName());
            default -> plugin.getLogger().warn("Unknown login message verb \"{}\" from {}",
                    action, backend.getServerInfo().getName());
        }
    }

    /**
     * Blocks server switches for players who have not logged in.
     *
     * This covers /server and any other plugin's connection requests, not just the
     * commands registered here - the hole is proxy-wide, so the fix has to be too.
     * The initial connection to the network is always allowed: the player has to
     * reach a backend before there is any login wall to pass.
     */
    @Subscribe
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        if (!enabled || !plugin.getConfigManager().isGuardAllSwitches()) {
            return null;
        }
        if (event.getPreviousServer() == null) {
            return null;
        }

        Player player = event.getPlayer();
        if (maySwitch(player)) {
            return null;
        }

        long grace = plugin.getConfigManager().getLoginGraceMillis();
        if (grace <= 0) {
            deny(event);
            return null;
        }

        // A player who just logged in may be on their way to a preferred server,
        // moved by the auth plugin itself. Velocity handles that redirect inline
        // on the backend connection but dispatches our login announcement through
        // the event manager, so the switch can overtake the very message that
        // authorises it even though it was sent second. Hold the decision open
        // briefly rather than refusing a player who is in fact logged in.
        return EventTask.resumeWhenComplete(awaitLogin(player.getUniqueId())
                .orTimeout(grace, TimeUnit.MILLISECONDS)
                .handle((ignored, error) -> {
                    pendingLogins.remove(player.getUniqueId());
                    if (!maySwitch(player)) {
                        deny(event);
                    }
                    return null;
                }));
    }

    private void deny(ServerPreConnectEvent event) {
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        plugin.getLanguageManager().send(event.getPlayer(), "not-authenticated");
        warnIfBridgeSilent();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        authenticatedOn.remove(id);

        // Never leave a parked switch holding a reference to a player who left
        CompletableFuture<Void> waiting = pendingLogins.remove(id);
        if (waiting != null) {
            waiting.complete(null);
        }
    }

    /**
     * If the gate is on but no backend has ever reported a login, the bridge is
     * almost certainly not installed - and every player is now stuck on their
     * first server. Say so plainly rather than letting it look like a bug here.
     */
    private void warnIfBridgeSilent() {
        if (warnedAboutSilentBridge || !authenticatedOn.isEmpty()) {
            return;
        }
        warnedAboutSilentBridge = true;
        plugin.getLogger().warn("Blocked a server switch, but no backend has ever announced a login on "
                + "{}. Install the companion plugin from backend-bridge/ on every backend, or set "
                + "authentication.require-login to false if this network has no login wall.",
                channel == null ? "<invalid channel>" : channel.getId());
    }
}
