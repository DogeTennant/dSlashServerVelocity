package com.dogetennant.slashserver;

import com.dogetennant.slashserver.access.AccessGate;
import com.dogetennant.slashserver.auth.AuthGate;
import com.dogetennant.slashserver.command.CommandRegistry;
import com.dogetennant.slashserver.command.SlashServerCommand;
import com.dogetennant.slashserver.config.ConfigManager;
import com.dogetennant.slashserver.lang.LanguageManager;
import com.dogetennant.slashserver.util.CooldownManager;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Gives every backend server its own slash command, so players type /hub
 * instead of /server hub.
 *
 * Velocity port of the BungeeCord SlashServer plugin.
 */
@Plugin(
        id = "slashserver",
        name = "SlashServer",
        version = BuildConstants.VERSION,
        description = "Per-server slash commands - /hub instead of /server hub.",
        authors = {"DogeTennant"}
)
public class SlashServer {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    //  Managers
    private ConfigManager configManager;
    private LanguageManager languageManager;
    private CooldownManager cooldownManager;
    private CommandRegistry commandRegistry;
    private AuthGate authGate;
    private AccessGate accessGate;

    @Inject
    public SlashServer(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    //
    // Lifecycle
    //

    /**
     * Servers are only registered on the proxy by the time initialization runs,
     * so command building has to wait until here rather than the constructor.
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        configManager = new ConfigManager(this);
        configManager.load();

        languageManager = new LanguageManager(this);
        languageManager.load();

        cooldownManager = new CooldownManager();

        // Registered before any command exists, so there is no window in which a
        // switch can happen unguarded
        authGate = new AuthGate(this);
        authGate.initialise();
        proxy.getEventManager().register(this, authGate);

        accessGate = new AccessGate(this);
        proxy.getEventManager().register(this, accessGate);

        commandRegistry = new CommandRegistry(this);
        commandRegistry.registerAll();
        accessGate.logNodes();

        registerAdminCommand();
    }

    /**
     * A proxy reload can add or remove backends, so the generated commands are
     * rebuilt to match the new server list.
     */
    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        logger.info("Proxy reloaded - rebuilding server commands");
        reload();
    }

    private void registerAdminCommand() {
        CommandManager manager = proxy.getCommandManager();
        CommandMeta meta = manager.metaBuilder("slashserver")
                .aliases("slashsrv")
                .plugin(this)
                .build();
        manager.register(meta, new SlashServerCommand(this));
    }

    /**
     * Re-reads both config files and rebuilds every generated command.
     *
     * @return false when a file failed to load; the commands are still rebuilt
     *         from whatever settings are currently in memory
     */
    public boolean reload() {
        boolean configOk = configManager.load();
        boolean langOk = languageManager.load();

        // Re-resolve the gate before commands come back, never after
        authGate.initialise();
        cooldownManager.clearAll();
        commandRegistry.registerAll();
        accessGate.logNodes();

        return configOk && langOk;
    }

    //
    // Accessors
    //

    public ProxyServer getProxy() {
        return proxy;
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public String getVersion() {
        return BuildConstants.VERSION;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }

    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    public AuthGate getAuthGate() {
        return authGate;
    }

    public AccessGate getAccessGate() {
        return accessGate;
    }
}
