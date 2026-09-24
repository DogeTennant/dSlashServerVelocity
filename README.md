# SlashServer for Velocity

Gives every backend server its own command, so players type `/hub` instead of
`/server hub`. A Velocity port of the BungeeCord
[SlashServer](https://www.spigotmc.org/resources/slashserver.75/) plugin.

Drop the jar in `plugins/`, start the proxy, done - every server in
`velocity.toml` gets a command named after it with no configuration.

## Compatibility

One jar covers every current Velocity line:

| Proxy | Java the **proxy** needs | Status |
|---|---|---|
| Velocity 4.1.x | 25 | Current |
| Velocity 3.5.1 | 21 | Last 3.x - tested against this |
| Velocity 3.4.x | 17 | Works |

Minecraft version support - 1.21.11 and newer included - comes entirely from the
proxy. The plugin is protocol-agnostic: it only moves players between servers
Velocity already knows about, and never touches a packet.

It is compiled against the 3.4.0 API on purpose. Velocity 4.x ships the same
`com.velocitypowered.api` classes, so a 3.4.0-built jar loads on all three
lines, while a 4.x-built one would drop 3.x support (and need JDK 25 just to
compile). To build against a different API version anyway:

```
mvn package -Dvelocity.api.version=4.1.1
```

## Commands

| Command | What it does |
|---|---|
| `/<server>` | Connect yourself to that server |
| `/<server> <player>` | Send someone else there |
| `/slashserver reload` | Re-read both config files and rebuild every command |
| `/slashserver list` | Show every registered command, its aliases and player count |
| `/slashserver help` | Usage |

`/slashsrv` is an alias for `/slashserver`.

## Permissions

| Node | Default | Grants |
|---|---|---|
| `slashserver.server.<server>` | everyone | Access to that server, only checked when `permissions.required` is on |
| `slashserver.server.bypass` | op | Access to every server |
| `slashserver.send.others` | op | Use `/<server> <player>` |
| `slashserver.send.override` | op | Send a player onto a server they have no node for |
| `slashserver.cooldown.bypass` | op | Skip the switch cooldown |
| `slashserver.admin` | op | `/slashserver` |

Servers are open to everyone out of the box. Set `permissions.required: true` to
lock them down.

### Per-server access

Every server has a node the moment it exists - `test` needs
`slashserver.server.test`, `hub` needs `slashserver.server.hub`. Nothing has to
be declared anywhere; Velocity has no permission registry, nodes are just strings
checked at runtime. The exact list is printed to the console on startup so you
never have to derive it yourself:

```
Per-server permissions are ON. Nodes: hub -> slashserver.server.hub,
survival -> slashserver.server.survival, test -> slashserver.server.test.
Holding slashserver.server.bypass allows every server.
```

In LuckPerms:

```
/lp group default permission set slashserver.server.hub true
/lp group staff   permission set slashserver.server.test true
/lp group admin   permission set slashserver.server.* true
```

Any server can override its node with `servers.<name>.permission`, and the
override applies everywhere the permission is checked, not just to its command.

**The check runs at connection level, not just on this plugin's commands.**
Velocity's own `/server` takes a single node (`velocity.command.server`) for the
entire network, so a player who can use it at all can name any backend and land
there - a private one included. Guarding only `/test` would leave `/server test`
wide open, so `guard-all-connections` (on by default) checks every route onto a
server: our commands, `/server`, and any other plugin that moves players.

The first connection to the network is exempt by default, since refusing it drops
the player off the network entirely and the server they land on is normally
decided by the `try` list rather than by them. If a forced host points at a
restricted server - a destination a player *can* choose - turn on
`check-initial-connection`.

### Sending a player somewhere they cannot go

`/<server> <player>` needs `slashserver.send.others`. The **player being moved**
needs the destination's own node, because that node governs who may be on a
server, not who may put them there.

`slashserver.send.override` lifts that for staff, so you can pull someone into a
private server to help them. Each use is logged with the node that was bypassed:

```
Steve was sent to test without holding slashserver.server.test - allowed by an override
```

Under the hood this issues a single-use pass for that one connection, valid for
five seconds. Skipping the check in the command would not be enough on its own -
the connection it starts still reaches the connection-level gate, where the
player would be turned away. The pass is scoped to one player and one server, so
it authorises the send it was issued for and nothing the player tries afterwards.

**The override never bypasses the login gate.** An unauthenticated player cannot
be sent anywhere, whoever asks.

## Security: the login gate

**If you run an offline-mode network with a login plugin, read this.**

Server commands are registered on the *proxy*. Velocity matches `/survival`
against its own command map, runs it, and **never forwards it to the backend**.
A login wall running on the backend therefore has nothing to cancel - a player
sitting at the login screen can type `/survival` and walk straight past it.
Velocity's own `/server` has exactly the same problem, so the fix cannot live
only in this plugin's commands.

The gate closes it by refusing to move a player the proxy has not seen
authenticate - including switches made by `/server` and by other plugins
(`guard-all-switches`). It **fails closed**: unknown player, no switch.

**Login state is tracked per server, not per session.** AuthPlayers keeps its
login state in a per-server list inside each backend's JVM, so passing the wall
on the lobby says nothing about the wall on survival. A player may leave a server
only once *that* server has reported them logged in.

This distinction is the whole security property. A single "logged in at least
once" flag reopens the hole one login later: the player authenticates on the
lobby, gets moved to their preferred server, lands at a wall they have not
passed - and can then hop freely around the network from behind it.

A backend that never reports a login would trap players on it, so any server
without a login wall of its own belongs in `no-login-required`.

`require-login: auto` (the default) turns the gate on when the proxy is in
offline mode and leaves it off in online mode, where there is no password wall
to bypass. Leaving it off on an offline-mode proxy logs a warning every start.

### Installing the bridge

The proxy cannot see login state on its own - it lives in the auth plugin's
memory on the backend. `backend-bridge/` is a small companion plugin that
announces logins over a plugin message channel:

```
cd backend-bridge && mvn package
```

Drop `SlashServerAuthBridge-1.0.0.jar` into `plugins/` on **every backend that
runs the login wall** and restart. It reads AuthPlayers reflectively through its
public `isAuthed(Player)` method, so it needs neither AuthPlayers nor CompleteAPI
to build, and disables itself with a clear message on servers where AuthPlayers
is absent.

Without the bridge and with the gate on, nobody can change servers - the proxy
logs exactly that, once, with the fix.

**Messages that do not originate from a backend server are rejected.** A modified
client can send plugin messages on any registered channel, so honouring a
client-sent one would hand every player a self-service bypass. The bridge sends
over the player's own connection and the proxy checks the payload UUID matches
the connection it arrived on, so one backend cannot authenticate a third party
either.

### Redirects straight after login

If your auth plugin moves players to a preferred server the moment they log in
(`/prefer survival`), that redirect races its own permission slip, and the naive
version of this gate refuses it - telling a player to log in when they just did.

Velocity is the reason. Looking at `BackendPlaySessionHandler`, a BungeeCord
`Connect` message is processed **inline** at the top of the packet handler, while
a plugin message on our own channel is dispatched through the event manager
(`fire(...).thenAcceptAsync`). So even though the bridge sends the login first,
the redirect that follows it can be acted on first.

Two things fix it together:

- The bridge hooks the auth event and announces **in the same tick** the password
  is accepted, rather than up to a poll later. In AuthPlayers the ordering is
  `authedPlayers.add` → `callEvent(PlayerAuthEvent)` → `redirectPlayer`, so the
  announcement is always sent before the redirect.
- The proxy holds a switch it is about to refuse for up to
  `login-grace-millis` (default 500ms), waiting to hear whether that player just
  logged in. It resolves the moment the announcement lands - typically a few
  milliseconds, not the full window - and only ever delays a switch that was
  going to be denied anyway.

Set `login-grace-millis: 0` to disable the wait; the event hook alone will handle
the common case, but the race is then back on the table.

## Configuration

Two files are written to `plugins/slashserver/` on first start:

- **`config.yml`** - which servers get commands, permissions, cooldown, aliases
- **`messages.yml`** - every user-facing string, in
  [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format

Both are fully commented. Highlights:

```yaml
mode: auto            # auto = every server gets a command
                      # manual = only servers listed under servers:

disabled-servers: []  # servers to skip in auto mode

override-existing: false   # never steal a command name from another plugin

servers:
  hub:
    command: hub
    aliases: [lobby, l]
    display-name: "<aqua>the Hub</aqua>"
```

Set any message to `""` to silence it - no empty chat line is sent.

Run `/slashserver reload` after editing; commands are unregistered and rebuilt
in place, so renamed commands take effect without a restart. Commands are also
rebuilt automatically after a proxy reload, since that can add or remove
backends.

### Behaviour worth knowing

- A command name already taken by Velocity or another plugin is **skipped**,
  with a warning naming the server, rather than silently hijacked. Flip
  `override-existing` if you want the opposite.
- A `servers:` entry naming a server the proxy doesn't have is logged on
  startup - that catches typos against `velocity.toml`.
- The cooldown only starts on a switch a player made themselves. Being denied,
  or being moved by staff, never puts a player on cooldown.
- If a config file fails to parse, the previously loaded settings stay live
  instead of the plugin falling mute.
- `online-check` (off by default) pings the backend first so a dead server gets
  your own message instead of Velocity's generic connection error. It costs a
  round trip on every command.

## Note: repeated logins on every server switch

If players are asked to log in again on each hop, that is **not** this plugin -
it happens with `/server` too. AuthPlayers has a session-resume path,
`DatabaseBridge.eligibleToPassAccess`, which skips the password when all three of
these hold:

1. `knownPlayer(player)` - the UUID is known,
2. `sameIp(player)` - the IP the backend sees matches the stored one,
3. the last `authDcs` row for that UUID is under `secureInterval` (**30 seconds**) old.

So resume depends on the **UUID** and the **IP the backend sees** staying
consistent across servers - which is precisely what
`player-info-forwarding-mode` controls. On BungeeCord that was `ip_forward:
true`; the Velocity equivalent is `modern` in `velocity.toml` plus
`velocity.enabled: true` and the matching `forwarding.secret` in each backend's
`config/paper-global.yml`. With forwarding off, every backend sees the proxy's
address instead of the player's, and both the check and its security guarantee
break down.

Two things worth knowing while you are in there, both in AuthPlayers rather than
here:

- With forwarding off, `sameIp` compares the proxy's address against the proxy's
  address - it matches for *everybody*. Session resume then lets anyone resume
  anyone else's session inside that 30-second window.
- The `online` table query uses `time_quit=NULL`, which is never true in SQL;
  `IS NULL` is probably what was intended.

## Building

```
mvn package
```

Produces `target/SlashServerVelocity-1.0.0.jar`. Nothing is shaded - Adventure,
MiniMessage and Configurate all come from the proxy.

`velocity-plugin.json` is generated at compile time from the `@Plugin`
annotation, with the version pulled from the POM through `BuildConstants`
(`src/main/java-templates/`), so the descriptor can never drift from the build.
