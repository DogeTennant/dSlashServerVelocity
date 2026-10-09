package com.dogetennant.slashserver.auth;

import com.dogetennant.slashserver.SlashServer;
import com.dogetennant.slashserver.config.ConfigManager;
import com.dogetennant.slashserver.lang.LanguageManager;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The proxy's login gate: who may leave the server they are on, as the backends report it. */
class AuthGateTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-0000000057e7");
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("slashserver:auth");

    private final ConfigManager config = mock(ConfigManager.class);
    private final Player alex = mock(Player.class);
    private final Player steve = mock(Player.class);
    private final RegisteredServer lobby = server("lobby");
    private final RegisteredServer survival = server("survival");
    private AuthGate gate;

    @BeforeEach
    void setUp() {
        SlashServer plugin = mock(SlashServer.class);
        ProxyServer proxy = mock(ProxyServer.class);
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        when(proxyConfig.isOnlineMode()).thenReturn(false);
        when(proxy.getConfiguration()).thenReturn(proxyConfig);
        when(proxy.getChannelRegistrar()).thenReturn(mock(ChannelRegistrar.class));
        when(plugin.getProxy()).thenReturn(proxy);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getLanguageManager()).thenReturn(mock(LanguageManager.class));
        when(config.getRequireLogin()).thenReturn("auto");          // offline-mode proxy: gate on
        when(config.getAuthChannel()).thenReturn("slashserver:auth");
        when(config.getServersWithoutLogin()).thenReturn(Set.of());
        when(config.isGuardAllSwitches()).thenReturn(true);
        when(config.getLoginGraceMillis()).thenReturn(0L);
        when(plugin.getConfigManager()).thenReturn(config);
        when(alex.getUniqueId()).thenReturn(ALEX);
        when(steve.getUniqueId()).thenReturn(STEVE);
        gate = new AuthGate(plugin);
        gate.initialise();
    }

    private static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, InetSocketAddress.createUnresolved(name, 25565)));
        return server;
    }

    /** The player's connection to that server, as the proxy sees it. */
    private static ServerConnection on(Player player, RegisteredServer server) {
        ServerInfo info = server.getServerInfo();
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServerInfo()).thenReturn(info);
        when(connection.getServer()).thenReturn(server);
        when(connection.getPlayer()).thenReturn(player);
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        return connection;
    }

    private static byte[] payload(String verb, UUID subject) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(verb);
        out.writeUTF(subject.toString());
        return bytes.toByteArray();
    }

    private PluginMessageEvent message(ChannelMessageSource from, String verb, UUID subject) throws IOException {
        PluginMessageEvent event = new PluginMessageEvent(from, mock(ChannelMessageSink.class), CHANNEL, payload(verb, subject));
        gate.onPluginMessage(event);
        return event;
    }

    @Test
    void aLoginTheBackendReportsLetsThePlayerLeaveThatServer() throws Exception {
        ServerConnection connection = on(alex, lobby);

        PluginMessageEvent event = message(connection, "LOGIN", ALEX);

        assertThat(gate.maySwitch(alex)).isTrue();
        assertThat(event.getResult().isAllowed()).isFalse();        // never passed on to anyone
    }

    @Test
    void aLoginAPlayersClientSentIsIgnored() throws Exception {
        on(alex, lobby);

        PluginMessageEvent event = message(alex, "LOGIN", ALEX);    // a modified client writing the channel

        assertThat(gate.maySwitch(alex)).isFalse();
        assertThat(event.getResult().isAllowed()).isFalse();
    }

    @Test
    void aBackendCannotLogInSomeoneElse() throws Exception {
        on(alex, lobby);

        message(on(steve, lobby), "LOGIN", ALEX);                  // over Steve's connection

        assertThat(gate.maySwitch(alex)).isFalse();
    }

    @Test
    void aPlayerTheProxyNeverHeardAboutCannotLeave() {
        on(alex, lobby);
        ServerPreConnectEvent switching = new ServerPreConnectEvent(alex, survival, lobby);

        gate.onServerPreConnect(switching);

        assertThat(switching.getResult().isAllowed()).isFalse();
    }

    @Test
    void theFirstConnectionIsAlwaysAllowed() {
        when(alex.getCurrentServer()).thenReturn(Optional.empty());
        ServerPreConnectEvent joining = new ServerPreConnectEvent(alex, lobby, null);

        gate.onServerPreConnect(joining);

        assertThat(joining.getResult().isAllowed()).isTrue();
    }

    @Test
    void aLoginCountsOnlyOnTheServerItWasReportedFrom() throws Exception {
        message(on(alex, lobby), "LOGIN", ALEX);

        on(alex, survival);                                         // survival has its own wall

        assertThat(gate.maySwitch(alex)).isFalse();
    }

    @Test
    void aServerWithoutALoginWallCanAlwaysBeLeft() {
        when(config.getServersWithoutLogin()).thenReturn(Set.of("survival"));
        on(alex, survival);

        assertThat(gate.maySwitch(alex)).isTrue();
    }

    @Test
    void aLogoutClosesTheGateAgain() throws Exception {
        ServerConnection connection = on(alex, lobby);
        message(connection, "LOGIN", ALEX);

        message(connection, "LOGOUT", ALEX);

        assertThat(gate.maySwitch(alex)).isFalse();
    }

    @Test
    void backOnTheServerOfTheLoginThePlayerMayLeaveItAgain() throws Exception {
        // Deliberate (owner decision 2026-10-09): once logged in during a connection, a player can
        // leave the lobby again even if its 30-second session resume has run out - it is the same
        // person in the same connection.
        when(config.getServersWithoutLogin()).thenReturn(Set.of("survival"));
        message(on(alex, lobby), "LOGIN", ALEX);
        on(alex, survival);

        on(alex, lobby);

        assertThat(gate.maySwitch(alex)).isTrue();
    }
}
