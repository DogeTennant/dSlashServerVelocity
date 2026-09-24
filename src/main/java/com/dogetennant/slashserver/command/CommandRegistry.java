package com.dogetennant.slashserver.command;

import com.dogetennant.slashserver.SlashServer;
import com.dogetennant.slashserver.config.ServerEntry;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the lifecycle of the generated per-server commands.
 *
 * Every alias we register is remembered so a reload can take them all back off
 * the proxy before rebuilding - otherwise renaming a command in config.yml would
 * leave the old one registered until restart.
 */
public class CommandRegistry {

    /**
     * A server and the command names it actually ended up with.
     *
     * These can differ from {@link ServerEntry#allCommands()} when a name was
     * dropped as a conflict, so anything reporting to admins uses this rather
     * than the configured names.
     *
     * @param aliases registered names, primary first; never empty
     */
    public record Registration(ServerEntry entry, List<String> aliases) {

        public Registration {
            aliases = List.copyOf(aliases);
        }

        public String primary() {
            return aliases.get(0);
        }

        public List<String> secondaryAliases() {
            return aliases.subList(1, aliases.size());
        }
    }

    private final SlashServer plugin;

    private final List<String> registeredAliases = new ArrayList<>();
    private final List<Registration> registrations = new ArrayList<>();

    public CommandRegistry(SlashServer plugin) {
        this.plugin = plugin;
    }

    /** Drops the current commands and rebuilds them from the live server list. */
    public void registerAll() {
        unregisterAll();

        CommandManager manager = plugin.getProxy().getCommandManager();
        List<ServerEntry> entries = plugin.getConfigManager()
                .buildEntries(plugin.getProxy().getAllServers());

        for (ServerEntry entry : entries) {
            List<String> usable = filterConflicts(manager, entry);
            if (usable.isEmpty()) {
                continue;
            }

            String primary = usable.get(0);
            String[] aliases = usable.subList(1, usable.size()).toArray(new String[0]);

            CommandMeta meta = manager.metaBuilder(primary)
                    .aliases(aliases)
                    .plugin(plugin)
                    .build();

            manager.register(meta, new ServerCommand(plugin, entry));
            registeredAliases.addAll(usable);
            registrations.add(new Registration(entry, usable));
        }

        plugin.getLogger().info("Registered {} server command(s) covering {} server(s)",
                registeredAliases.size(), registrations.size());
    }

    public void unregisterAll() {
        CommandManager manager = plugin.getProxy().getCommandManager();
        for (String alias : registeredAliases) {
            manager.unregister(alias);
        }
        registeredAliases.clear();
        registrations.clear();
    }

    /**
     * Skips aliases another plugin (or Velocity itself) already owns.
     *
     * Taking over /server or /send by accident because a backend happens to be
     * named that way is a much worse failure than not registering it, so the
     * default is to leave the existing command alone and say so in the log.
     */
    private List<String> filterConflicts(CommandManager manager, ServerEntry entry) {
        boolean override = plugin.getConfigManager().isOverrideExisting();
        List<String> usable = new ArrayList<>();

        for (String alias : entry.allCommands()) {
            if (manager.hasCommand(alias)) {
                if (!override) {
                    plugin.getLogger().warn("Skipping /{} for server \"{}\" - that command is already "
                                    + "registered. Rename it under servers.{}.command in config.yml, or set "
                                    + "override-existing: true.",
                            alias, entry.serverName(), entry.serverName());
                    continue;
                }
                plugin.getLogger().warn("Overriding the existing /{} command with the \"{}\" server command",
                        alias, entry.serverName());
                manager.unregister(alias);
            }
            usable.add(alias);
        }
        return usable;
    }

    /** The servers currently backed by a live command, for {@code /slashserver list}. */
    public List<Registration> getRegistrations() {
        return List.copyOf(registrations);
    }
}
