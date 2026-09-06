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