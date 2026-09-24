package com.dogetennant.slashserver.config;

import java.util.List;

/**
 * One fully resolved server -> command mapping.
 *
 * Built by {@link ConfigManager} from the proxy's registered servers plus any
 * per-server overrides in config.yml, so everything the command needs at
 * runtime is already decided here - no config lookups on execution.
 *
 * @param serverName  the name as registered on the proxy (velocity.toml)
 * @param command     primary command, without the leading slash, lower-cased
 * @param aliases     extra commands that do the same thing, lower-cased
 * @param permission  permission node required to use it
 * @param displayName MiniMessage name used in messages; falls back to serverName
 */
public record ServerEntry(String serverName,
                          String command,
                          List<String> aliases,
                          String permission,
                          String displayName) {

    public ServerEntry {
        aliases = List.copyOf(aliases);
    }

    /** Primary command plus aliases, in registration order. */
    public List<String> allCommands() {
        if (aliases.isEmpty()) {
            return List.of(command);
        }
        List<String> all = new java.util.ArrayList<>(aliases.size() + 1);
        all.add(command);
        all.addAll(aliases);
        return all;
    }
}
