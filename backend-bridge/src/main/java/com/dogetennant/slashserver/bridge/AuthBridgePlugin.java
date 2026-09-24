package com.dogetennant.slashserver.bridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells the Velocity proxy when a player has logged in.
 *
 * The proxy cannot see AuthPlayers' login state - it lives in a per-server list
 * inside the backend JVM - so without something like this the proxy has no way
 * to know who is allowed to change servers, and /survival typed at the login
 * screen walks straight past the wall.
 *
 * AuthPlayers is read reflectively through its public isAuthed(Player) method
 * and its auth event is hooked by class name, so this compiles and runs without
 * AuthPlayers or CompleteAPI on the classpath.
 *
 * Two routes report a login, because neither alone is sufficient:
 *
 *  - the auth event, which fires the instant the password is accepted. This is
 *    what makes a post-login redirect to a preferred server work: AuthPlayers
 *    fires the event before it sends the redirect, so the proxy is told in the
 *    same tick rather than up to a poll later.
 *  - a slow poll of isAuthed, which catches every other route into the authed
 *    state - an admin using /auth, and the IP session resume, which lets a
 *    returning player straight in without firing anything.
 */
public class AuthBridgePlugin extends JavaPlugin implements Listener {

    private static final String LOGIN = "LOGIN";
    private static final String LOGOUT = "LOGOUT";

    private String channel;
    private Method isAuthed;
    private Plugin authPlayers;

    /** Players already announced, so each state change is sent exactly once. */
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        channel = getConfig().getString("channel", "slashserver:auth");
        String eventClass = getConfig().getString("auth-event-class",
                "gs.completeapi.event.PlayerAuthEvent");
        long pollTicks = Math.max(1L, getConfig().getLong("poll-interval-ticks", 10L));

        authPlayers = Bukkit.getPluginManager().getPlugin("AuthPlayers");
        if (authPlayers == null) {
            getLogger().warning("AuthPlayers is not installed on this server - nothing to bridge. "
                    + "Install this plugin only on backends that run the login wall.");
            setEnabled(false);
            return;
        }

        try {
            isAuthed = authPlayers.getClass().getMethod("isAuthed", Player.class);
        } catch (NoSuchMethodException e) {
            getLogger().severe("AuthPlayers has no isAuthed(Player) method - cannot bridge login state. "
                    + "The proxy will keep every player on their first server until this is fixed.");
            setEnabled(false);
            return;
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, channel);
        getServer().getPluginManager().registerEvents(this, this);
        hookAuthEvent(eventClass);
        getServer().getScheduler().runTaskTimer(this, this::poll, pollTicks, pollTicks);

        getLogger().info("Announcing AuthPlayers logins to the proxy on " + channel);
    }

    //
    // Instant path: the auth event
    //

    /**
     * Registers a listener for an event class this plugin does not compile
     * against, so no CompleteAPI jar is needed to build.
     *
     * Runs at LOWEST so the announcement is sent before any other handler of the
     * same event gets a chance to move the player somewhere.
     */
    @SuppressWarnings("unchecked")
    private void hookAuthEvent(String className) {
        Class<?> raw;
        try {
            raw = Class.forName(className);
        } catch (ClassNotFoundException e) {
            getLogger().warning(className + " was not found, so logins are only picked up by polling. "
                    + "A redirect fired immediately after login may be refused by the proxy. "
                    + "Correct auth-event-class in config.yml if your auth plugin fires a different event.");
            return;
        }

        if (!Event.class.isAssignableFrom(raw)) {
            getLogger().warning(className + " is not a Bukkit event - falling back to polling only.");
            return;
        }

        final Method getPlayer;
        try {
            getPlayer = raw.getMethod("getPlayer");
        } catch (NoSuchMethodException e) {
            getLogger().warning(className + " has no getPlayer() - falling back to polling only.");
            return;
        }

        getServer().getPluginManager().registerEvent(
                (Class<? extends Event>) raw,
                this,
                EventPriority.LOWEST,
                (listener, event) -> onAuthEvent(event, getPlayer),
                this,
                true);

        getLogger().info("Hooked " + className + " - logins are announced immediately");
    }

    private void onAuthEvent(Event event, Method getPlayer) {
        try {
            if (!(getPlayer.invoke(event) instanceof Player player)) {
                return;
            }

            // AuthPlayers adds the player to its authed list before firing this,
            // so the state is already true here and this stays a confirmation
            // rather than an assumption about what the event means
            if (isAuthed(player) && announced.add(player.getUniqueId())) {
                announce(player, LOGIN);
            }
        } catch (Exception e) {
            getLogger().warning("Could not handle auth event: " + e);
        }
    }

    //
    // Backstop: polling
    //

    private void poll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean authed = isAuthed(player);
            boolean known = announced.contains(player.getUniqueId());

            if (authed && !known) {
                announced.add(player.getUniqueId());
                announce(player, LOGIN);
            } else if (!authed && known) {
                // Logged back out without leaving - close the gate again
                announced.remove(player.getUniqueId());
                announce(player, LOGOUT);
            }
        }
    }

    private boolean isAuthed(Player player) {
        try {
            return (Boolean) isAuthed.invoke(authPlayers, player);
        } catch (Exception e) {
            getLogger().warning("AuthPlayers.isAuthed failed for " + player.getName() + ": " + e);
            return false;
        }
    }

    /**
     * A quit here is usually a server switch, not a disconnect. Only the local
     * bookkeeping is cleared, so this server will announce again if the player
     * comes back and authenticates again.
     *
     * No logout is sent: the proxy records which server a player is logged in on
     * and a switch makes that record stale by itself, so announcing one here
     * would at best be redundant and at worst cancel the login the player is
     * mid-switch on.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        announced.remove(event.getPlayer().getUniqueId());
    }

    //
    // Messaging
    //

    /**
     * Sent over the player's own connection, which is what lets the proxy verify
     * the message is about the player it arrived for. The proxy rejects anything
     * on this channel that did not come from a backend server.
     */
    private void announce(Player player, String action) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(action);
            out.writeUTF(player.getUniqueId().toString());
            player.sendPluginMessage(this, channel, bytes.toByteArray());
        } catch (Exception e) {
            getLogger().warning("Could not announce " + action + " for " + player.getName() + ": " + e);
        }
    }
}
