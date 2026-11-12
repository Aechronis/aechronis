# Punishments API

Read-only JSON API for bans.aechronis.net, served by the `punishments` module.

- Listens on `127.0.0.1:8126`. Change the port with `-Daechronis.punishments.apiPort=<port>`; `0` turns it off.
- In production, nginx proxies `/api/` to it and sets `X-Real-IP`.
- Every response has `Access-Control-Allow-Origin: *`, so the frontend can be hosted anywhere.
- Only `GET` is accepted. IP addresses are never returned.
- Times are Unix epoch milliseconds. A `null` `expiresAt` means the record never expires.
- Errors look like `{"error": "message"}`.

## Record

Every endpoint returns punishments in this shape:

```json
{
  "id": 12,
  "offence": "3f0c…",
  "player": { "uuid": "…", "name": "Steve" },
  "type": "ban",
  "typeLabel": "Ban",
  "category": "major_chat",
  "categoryLabel": "Major chat violation",
  "reason": "Major chat violation",
  "staff": { "uuid": "…", "name": "ModName" },
  "createdAt": 1762700000000,
  "expiresAt": 1763304800000,
  "active": true,
  "lifted": null
}
```

- `offence`: records created together for one offence share this id. For example, the second major chat offence creates a 7 day ban and a permanent mute.
- `type`: one of `warn`, `mute`, `ban`, `iteration_ban`, `ip_ban`, `blacklist`.
- `category`: the offence category id (`minor_chat`, `major_chat`, `minor_exploit`, `major_exploit`, `illegal_client`, `ban_evasion`), or `null` when staff set the punishment by hand (`/ban`, `/mute` and so on).
- `staff.uuid`: `null` when the punishment came from the console.
- `active`: `false` once the record expires or is lifted. Warnings are never active.
- `lifted`: `{ "at": 1762800000000, "by": "ModName", "reason": "Appeal accepted" }` once staff lift the record, otherwise `null`.

## `GET /api/blacklist`

```json
{
  "rule": "…",
  "players": [ /* Record, type "blacklist", not lifted, newest first */ ],
  "groups": [
    {
      "name": "SomeClan",
      "reason": "Duping ring",
      "staffName": "ModName",
      "createdAt": 1762700000000
    }
  ]
}
```

Groups are blacklisted by name only; their players are not tracked.

## `GET /api/recent?limit=50`

`{ "records": [Record, …] }`, newest first. `limit` is 1 to 200 and defaults to 50.

## `GET /api/player?q=<name or uuid>`

Looks up a player who has joined the server or has been punished. The name match is case-insensitive and uses the last known name. Returns 404 if the player isn't found. Each IP is limited to 60 lookups a minute, after which the endpoint returns 429.

```json
{
  "uuid": "…",
  "name": "Steve",
  "discord": { "id": "123456789012345678", "username": "steve" },
  "status": { "blacklisted": false, "banned": true, "muted": false },
  "offences": { "minor_chat": 2, "major_chat": 0, "minor_exploit": 0, "major_exploit": 0, "illegal_client": 0, "ban_evasion": 0 },
  "history": [ /* Record, newest first */ ]
}
```

- `discord`: `null` when the player hasn't linked an account, or when the Discord bot is offline.
- `discord.username`: `null` when Discord couldn't be reached.
- `status.banned`: `true` for any active ban, iteration ban, IP ban or blacklist.

## Commands

In game and in Discord the commands are identical. Discord takes each argument as its own option.

| Command | Permission |
| --- | --- |
| `/punish <player> <category>`: shows the player's earlier offences and the guideline's recommendation | `punishments.punish` |
| `/punish <player> <category> <warn\|mute\|ban\|blacklist> [time] [reason]`: staff pick the type and time; tab-complete and Discord autocomplete mark the guideline's pick as recommended | `punishments.punish` |
| `/warn <player> <reason>` | `punishments.warn` |
| `/mute <player> <time> [reason]`, `/unmute <player> [reason]` | `punishments.mute` |
| `/ban <player> <time> [reason]`, `/ipban <player> [reason]`, `/unban <player> [reason]` | `punishments.ban` |
| `/history <player>` | `punishments.history` |
| `/blacklist add\|remove <player> [reason]`, `/blacklist group add <name> [reason]`, `/blacklist group remove <name>` | `punishments.blacklist` |
| `/evidence <record> <link>`: adds a clip link to a punishment | `punishments.punish` |
| `/liftiterationbans confirm`: lifts every iteration ban and the IP bans issued with them | `punishments.admin` |

- **Times:** `1h`, `1d`, `3d`, `1w`, `2w`, `iteration` (bans only) or `perm`. Any time like `30m` or `1d12h` also works. Warnings and blacklists take no time.
- **Evidence in Discord:** the Discord commands also have an `evidence` option for a clip link. In game, put the link in the reason.

## Evidence

Evidence is staff-only. It shows in `/history` and the Discord log, and the API never returns it.

- **Links:** any link in a reason, such as a Medal clip from a ticket, is saved as evidence and removed from the public reason. Discord commands also have an `evidence` option, and log posts have an Add evidence button for clips that arrive later.
- **Chat:** after a chat punishment, a manual mute or a warning, staff can pick the player's recent messages as evidence. In game a menu opens. From Discord, a select menu follows the command reply. The server keeps each player's last 18 messages in memory, so they're gone after a restart.

## Discord

The discord module adds:

- **Punishment log:** with `DISCORD_PUNISHMENT_LOG_CHANNEL_ID` set, the bot posts every punishment to that channel.
  - Lifts and evidence update the same post, so each punishment stays one message. A punishment posted before a bot restart gets a separate message instead.
  - Linked staff can press Lift, with an optional reason, or Add evidence to paste clip links. Lift needs the node of the command that lifts that type (`punishments.warn`, `.mute`, `.ban` or `.blacklist`); Add evidence needs `punishments.punish`.
- **Slash commands:** `/punish`, `/warn`, `/mute`, `/unmute`, `/ban`, `/unban`, `/ipban`, `/history`, `/blacklist` and `/liftiterationbans` take their arguments as separate Discord options, and the player and duration options autocomplete.
  - They run as the staff member's linked Minecraft account and check the same permissions as in game.
  - Discord hides them from members without Timeout Members unless server settings say otherwise.
