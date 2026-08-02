# Aechronis

This repository contains the Aechronis server, its Kotlin modules, guides, and resource pack.

## Layout

- `server/` assembles the runnable server.
- `modules/` contains `utils`, `combat`, `nodes`, `vanilla`, `worldedit`, `logger`, and `watchdog`.
- `resource-pack/` is the server's single resource pack.
- `guides/` contains the mdBook site.

## Build

The JVM projects share one Gradle build:

Run all JVM checks with `./gradlew check`. Build the deployable server with
`./gradlew :server:shadowJar`; the output is `server/build/libs/aechronis-all.jar`.

Server and resource-pack releases are created from `server-*` and `resource-pack-*` tags,
respectively.

Server data paths are relative to the process working directory. Run the JAR from a dedicated
deployment directory rather than storing live world or module data in this repository.
