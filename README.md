# Aechronis

This repository contains the Aechronis server, its Kotlin modules, guides, and resource packs.

## Layout

- `server/` contains the bootstrap, permanent integrations and services, and runtime JAR module loader.
- `modules/` contains the independently loaded gameplay module projects. `modules/iterations/a-new-millenium` is the current gameplay composition; `modules/iterations/template` remains the baseline for future iterations. Any runtime module can keep a matching pack in its own `resource-pack/` directory. `modules/misc` is the build-time integration library embedded into the permanent server JAR.
- `modules/nodes/resource-pack/` owns minimap and relationship-hitbox assets, `modules/combat/resource-pack/` owns combat's hidden glow-lichen and cloud assets, and each iteration module owns its remaining client assets. The active iteration additionally requests its external Ashen base pack.
- `guides/` contains the mdBook site.

## Build

The JVM projects share one Gradle build:

Run `./gradlew build` to check every JVM project and build each of its JARs.
Run `./gradlew serverDistributionZip` to assemble the complete runnable layout
under `build/distributions/aechronis/` and its archive at
`build/distributions/aechronis-server.zip`. The layout keeps the core at
`aechronis.jar` and every gameplay JAR directly under `modules/`; deploy both.

Runtime modules are built independently. For example,
`./gradlew :modules:combat:build` produces the combat module JAR in
`modules/combat/build/libs`, while the current iteration module uses
`./gradlew :modules:iterations:a-new-millenium:build` and
`modules/iterations/a-new-millenium/build/libs`. These JARs can be copied directly to
the server's module directory. A runtime module with a `resource-pack/pack.mcmeta`
also contains that pack, so its gameplay and client assets are deployed together.
The module manager discovers these packs automatically; modules do not need a
player listener or initialization code to apply them.

Dependencies used only by a runtime module belong on its `moduleApi` or
`moduleImplementation` configuration and are shaded into that module's
deployable JAR. Dependencies supplied by the core server or another module stay
on `compileOnly` and are not duplicated.

Every runtime module JAR provides `net.aechronis.server.modules.AechronisModule` through
Java's service-provider mechanism. The server rejects invalid or duplicate IDs
and dependency cycles before enabling a module. A module whose dependency is
missing or disabled remains discovered but disabled, with the reason visible in
`/modules info`.

## Runtime module management

Server operators with the `server.modules.manage` permission can inspect and
change the runtime module set without restarting the Minecraft server:

- `/modules list` lists discovered modules and their current state.
- `/modules info <id>` shows a module's dependencies and dependants.
- `/modules load <id>` (or `enable`) enables a module and its dependencies.
- `/modules unload <id>` (or `disable`) disables a module when nothing enabled
  depends on it. Add `cascade` to also disable its enabled dependants.
- `/modules restart <id>` restarts the module runtime with the selected module.
- `/modules reload` (or `rescan`) scans the module directory and applies added,
  removed, or replaced JARs.

All module JARs belong to one classloader generation. A successful runtime
change therefore restarts the enabled module graph in dependency order, even
when the command names one module. The operation is validated before the live
generation is stopped, and an invalid candidate leaves the running generation
unchanged. If a validated candidate fails while starting, the manager rebuilds
the previous generation from its staged JAR copies. Explicit enable/disable
choices survive server restarts in `modules/.disabled-modules`.

Official distributions include `modules/.required-modules`. Startup and reload
fail closed if one of those module JARs is absent. A listed module may be
inactive only after an operator explicitly disables it. Custom deployments can
provide their own manifest or omit it when intentionally running a different
non-empty module set. An empty module directory is rejected so a legacy
core-JAR-only deployment cannot silently start without gameplay.

Iteration composition modules are alternatives, not add-ons. Remove an old
`template.jar` when deploying `a-new-millenium.jar`; the module graph rejects a
generation containing both to prevent duplicate item and listener registration.

Do not overwrite a live JAR in place. Copy the replacement beside it using a
non-`.jar` suffix, then atomically rename it on the same filesystem before
running `/modules rescan`:

```sh
cp a-new-millenium-new.jar modules/a-new-millenium.jar.part
mv modules/a-new-millenium.jar.part modules/a-new-millenium.jar
```

To remove a module, unload it (using `cascade` when required), move its JAR out
of the module directory, and run `/modules rescan`. The `a-new-millenium` module
depends on the complete default gameplay graph, so unloading one of its
dependencies also requires unloading `a-new-millenium`.

Each runtime module shadow JAR embeds a pack when that module has a `resource-pack/`
directory. When the module loads, the core extracts and serves the pack from
`resource-packs/<module-id>/` beside the server JAR, then applies the complete
active module pack stack to players. In an exploded development run it serves
each module's source directory directly.

Server data paths are relative to the process working directory. Run the JAR from a dedicated
deployment directory rather than storing live world or module data in this repository.
