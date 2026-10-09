package com.dogetennant.slashserver.access;

import com.dogetennant.slashserver.SlashServer;
import com.dogetennant.slashserver.config.ConfigManager;
import com.dogetennant.slashserver.lang.LanguageManager;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Per-server permissions, checked on every route onto a server. */
class AccessGateTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");

    private final ConfigManager config = mock(ConfigManager.class);
    private final Player alex = mock(Player.class);
    private final RegisteredServer lobby = server("lobby");
    private final RegisteredServer staff = server("staff");
    private final RegisteredServer events = server("events");
    private AccessGate gate;

    @BeforeEach
    void setUp() {
        SlashServer plugin = mock(SlashServer.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getLanguageManager()).thenReturn(mock(LanguageManager.class));
        when(config.isRequirePermission()).thenReturn(true);
        when(config.isGuardAllConnections()).thenReturn(true);
        when(config.getAccessBypassPermission()).thenReturn("slashserver.server.bypass");
        when(config.permissionFor(anyString())).thenAnswer(call -> "slashserver.server." + call.getArgument(0));
        when(plugin.getConfigManager()).thenReturn(config);
        when(alex.getUniqueId()).thenReturn(ALEX);
        when(alex.getUsername()).thenReturn("Alex");
        gate = new AccessGate(plugin);
    }

    private static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, InetSocketAddress.createUnresolved(name, 25565)));
        return server;
    }

    /** Alex, on the lobby, heading for {@code target} by any route (/server, a plugin, a command). */
    private boolean allowed(RegisteredServer target) {
        ServerPreConnectEvent event = new ServerPreConnectEvent(alex, target, lobby);
        gate.onServerPreConnect(event);
        return event.getResult().isAllowed();
    }

    @Test
    void aServerNeedsItsOwnNode() {
        when(alex.hasPermission("slashserver.server.events")).thenReturn(true);

        assertThat(allowed(events)).isTrue();
        assertThat(allowed(staff)).isFalse();      // e.g. Velocity's /server staff with velocity.command.server
    }

    @Test
    void theBypassNodeOpensEveryServer() {
        when(alex.hasPermission("slashserver.server.bypass")).thenReturn(true);

        assertThat(allowed(staff)).isTrue();
    }

    @Test
    void aStaffPassLetsOneConnectionThrough() {
        gate.authoriseOnce(ALEX, "staff");         // /staff Alex by someone with the override

        assertThat(allowed(staff)).isTrue();
        assertThat(allowed(staff)).isFalse();      // used up
    }

    @Test
    void aPassIsOnlyForTheServerItWasGivenFor() {
        gate.authoriseOnce(ALEX, "events");

        assertThat(allowed(staff)).isFalse();
    }

    @Test
    void withPermissionsOffEveryoneGetsEverywhere() {
        when(config.isRequirePermission()).thenReturn(false);

        assertThat(allowed(staff)).isTrue();
    }

    @Test
    void aSwitchTheLoginGateRefusedStaysRefused() {
        ServerPreConnectEvent event = new ServerPreConnectEvent(alex, events, lobby);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        when(alex.hasPermission("slashserver.server.events")).thenReturn(true);

        gate.onServerPreConnect(event);

        assertThat(event.getResult().isAllowed()).isFalse();
    }
}
