# Canyon [![Build](https://github.com/njq8x/canyon/actions/workflows/build.yml/badge.svg)](https://github.com/njq8x/canyon/actions/workflows/build.yml) [![Soak](https://github.com/njq8x/canyon/actions/workflows/soak.yml/badge.svg)](https://github.com/njq8x/canyon/actions/workflows/soak.yml) [![Discord](https://img.shields.io/discord/627985817020923937)](https://discord.gg/hj5m5z3) [![forthebadge](https://forthebadge.com/images/badges/contains-technical-debt.svg)](https://forthebadge.com)

Canyon is a fork of CraftBukkit for Minecraft Beta 1.7.3. It includes multiple
enhancements whilst also retaining compatibility with old Bukkit plugins and
mods as much as possible.

## Building

Requirements: JDK 8 and Maven 3.6+. The build applies the patch sets in
`Bukkit-Patches` and `CraftBukkit-Patches` using `git am`, so git needs a
configured identity first:

```bash
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
```

1. Clone the repo with `git clone --recursive https://github.com/njq8x/canyon.git`.
2. Go into the new `canyon` directory and run `./canyon p` to apply the patches.
3. Run `mvn clean package`.
4. Find the server JAR in `Canyon-Server/target/canyon-server.jar` and the API
   JAR in `Canyon-API/target/canyon-api.jar`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the patch workflow, which is how all
changes to Canyon are made.

## Downloading

Tagged builds are published as GitHub releases:

* [Releases](https://github.com/njq8x/canyon/releases) — server jar, API jar and checksums
* Every push to `master` also uploads the server jar as a workflow artifact

CI builds every push and pull request, and runs an integration smoke test that
boots the server, logs in a protocol-level client, streams chunks, saves, and
shuts down headlessly. A connection soak test runs on every build as well, and
a longer one weekly (see [Testing](#testing)).

## Testing

All test scripts build their own disposable server in a temporary directory and
shut it down afterwards, so they never touch an existing world. Pass the server
and API jars built by Maven:

```bash
./scripts/smoke-test.sh     Canyon-Server/target/canyon-server.jar Canyon-API/target/canyon-api.jar
./scripts/soak.sh           Canyon-Server/target/canyon-server.jar Canyon-API/target/canyon-api.jar
./scripts/chunkfail-test.sh Canyon-Server/target/canyon-server.jar Canyon-API/target/canyon-api.jar
./scripts/benchmark.sh      Canyon-Server/target/canyon-server.jar Canyon-API/target/canyon-api.jar
```

* `smoke-test.sh` — one login, chunk download, save and clean shutdown.
* `chunkfail-test.sh` — a world generator that throws for one chunk must not
  hang the chunk-load future or poison the load queue for later requests.
* `soak.sh` — repeated parallel logins with idling and clean disconnects; fails
  on leaked sockets or threads, timed out connections, errors in the log, or a
  server that stops answering. Set `SOAK_NETTY=1` to exercise the Netty stack,
  and see the header of the script for the bot/cycle/idle knobs.
* `benchmark.sh` — reproducible load benchmark (idle ticks, chunk generation,
  chunk churn, entities). Run it manually or via the weekly Benchmark workflow;
  absolute numbers depend on the machine.

The protocol bot used by the smoke and soak tests lives in `scripts/bot`.

## Differences from CraftBukkit

Canyon features:

* Bug fixes.
* Select technological backports from modern Minecraft servers (such as Spigot
  and Paper). Canyon will not import "future" gameplay features as a default.
  We aim to retain and improve the Beta 1.7.3 gameplay experience.
* Performance enhancements (some backported from Spigot and Paper, others specific
  to Canyon).
* Code quality improvements.
* New APIs, to complete the Beta 1.7.3 modding story and adapt to the modern
  Java ecosystem.

To support these new features, Canyon depends on Java 8 and higher and integrates
modern libraries (such as the replacement for Google Collections, Google Guava).
Legacy Bukkit plugins are fully supported through the [Colorado](https://github.com/canyonmodded/colorado)
compatibility layer, which dynamically rewrites legacy plugins to adapt to new
Canyon internals (although we make it a goal to minimize disruptive changes to
the Minecraft core).

## Credits

* [Paper](https://papermc.io/): build system and some backports. Tacos, Taylor
  Swift, and cats are amazing.
* [Modification Station](https://discord.gg/8Qky5XY): advice, contributions,
  and testers. A great community to be in if you're interested in the old
  pre-release version of Minecraft, from pre-alpha to beta 1.8.
