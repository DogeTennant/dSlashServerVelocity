package com.dogetennant.slashserver.command;

import com.dogetennant.slashserver.SlashServer;
import com.dogetennant.slashserver.config.ServerEntry;
import com.dogetennant.slashserver.lang.LanguageManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Admin command: {@code /slashserver reload|list|help}.
 *
 * Guarded by a single permission node, {@value #PERMISSION}.
 */
public class SlashServerCommand implements SimpleCommand {

    public static final String PERMISSION = "slashserver.admin";

    private final SlashServer plugin;

    public SlashServerCommand(SlashServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(source);
            case "list" -> handleList(source);
            case "help" -> sendHelp(source);
            default -> plugin.getLanguageManager().send(source, "admin.unknown-subcommand",
                    Placeholder.unparsed("input", args[0]));
        }
    }

    private void handleReload(CommandSource source) {
        LanguageManager lang = plugin.getLanguageManager();

        if (plugin.reload()) {
            lang.send(source, "admin.reloaded", Placeholder.unparsed("count",
                    String.valueOf(plugin.getCommandRegistry().getRegistrations().size())));
        } else {
            lang.send(source, "admin.reload-failed");
        }
    }

    private void handleList(CommandSource source) {
        LanguageManager lang = plugin.getLanguageManager();
        List<CommandRegistry.Registration> registrations = plugin.getCommandRegistry().getRegistrations();

        if (registrations.isEmpty()) {
            lang.send(source, "admin.list-empty");
            return;
        }

        lang.send(source, "admin.list-header",
                Placeholder.unparsed("count", String.valueOf(registrations.size())));

        // Names come from the registration, not the config, so a command dropped
        // as a conflict is never listed as if players could type it
        for (CommandRegistry.Registration registration : registrations) {
            ServerEntry entry = registration.entry();
            Optional<RegisteredServer> server = plugin.getProxy().getServer(entry.serverName());
            int online = server.map(value -> value.getPlayersConnected().size()).orElse(0);

            lang.send(source, "admin.list-entry",
                    Placeholder.unparsed("server", entry.serverName()),
                    Placeholder.parsed("display", entry.displayName()),
                    Placeholder.unparsed("command", registration.primary()),
                    Placeholder.unparsed("aliases", registration.secondaryAliases().isEmpty()
                            ? "-" : String.join(", ", registration.secondaryAliases())),
                    Placeholder.unparsed("permission", entry.permission()),
                    Placeholder.unparsed("online", String.valueOf(online)));
        }
    }

    private void sendHelp(CommandSource source) {
        plugin.getLanguageManager().send(source, "admin.help",
                Placeholder.unparsed("version", plugin.getVersion()));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1) {
            return List.of();
        }

        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return List.of("reload", "list", "help").stream()
                .filter(sub -> sub.startsWith(prefix))
                .toList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
