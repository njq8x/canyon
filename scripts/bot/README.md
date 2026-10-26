# Protocol bot

`BotClient.java` is a minimal Minecraft Beta 1.7.3 client used by
`scripts/smoke-test.sh` and `scripts/soak.sh`. It speaks the real wire protocol:
handshake, login, spawn position, chunk streaming and disconnect.

Packet framing is not hand-written. The bot calls the server's own
`net.minecraft.server.Packet` decoder, so it cannot drift from the protocol the
server actually speaks. That also means it has to be compiled against the built
server jar:

```bash
javac -source 8 -target 8 -cp Canyon-Server/target/canyon-server.jar \
    -d /tmp/bot scripts/bot/BotClient.java
java -cp Canyon-Server/target/canyon-server.jar:/tmp/bot BotClient
```

## Arguments

```
BotClient [host] [port] [username] [chunks] [timeout-ms] [idle-ms] [keepalives]
```

| Argument | Default | Meaning |
|---|---|---|
| `host` | `127.0.0.1` | server address |
| `port` | `25565` | server port |
| `username` | `SmokeBot` | player name (must be unique while connected) |
| `chunks` | `10` | distinct chunks to wait for |
| `timeout-ms` | `15000` | overall deadline for the session |
| `idle-ms` | `0` | stay connected this long after the chunks arrived |
| `keepalives` | `0` | keepalives to wait for (and answer) before leaving |

`chunks` counts *distinct* chunks, not chunk packets. The Netty stack sends every
queued chunk immediately (about 500 around spawn), while the legacy stack
throttles to a few per tick, so packet counts are not comparable between stacks.

## Output

```
BOT login entity_id=599 dimension=0
BOT spawn x=-122 y=64 z=-60
BOT chunks=90 unique_chunks=86 keepalives=0 spawn=true
BOT timing login_ms=260.5 chunks_ms=296.0 total_ms=1325.1
BOT PASSED
```

`login_ms` is handshake-to-login, `chunks_ms` handshake-to-last-wanted-chunk and
`total_ms` the whole session. On failure the bot prints `BOT FAILED: ...` and
exits non-zero; a kick exits with status 2.

## Keepalives

Beta 1.7.3 servers do not actually send keepalives: `NetServerHandler.a()` exists
but is never called, so the counter it uses never advances. The bot answers any
keepalive it does receive (like a real client would), which keeps the option
useful if the server ever starts sending them.
