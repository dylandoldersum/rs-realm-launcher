# RS-Realm Launcher

A small RuneLite-style launcher for the RS-Realm client. Checks a version manifest, then shows
**Download** (nothing installed), **Update** (out of date), or **Play now** (up to date). On play it
runs the downloaded `fiddled-client.jar` with the same Java runtime.

Pure JDK — no third-party dependencies. Requires **Java 21** to build and run.

## Build

```
./gradlew jar
```

Output: `build/libs/rs-realm-launcher.jar`. Players run it with:

```
java -jar rs-realm-launcher.jar
```

(or double-click it if Java 21 is the default `.jar` handler).

## How versioning works

1. Build the client fat jar (`gradlew fatJar` in the Fiddled Client project → `build/dist/fiddled-client.jar`).
2. Upload it as a GitHub **release asset**.
3. Edit `version.properties` (bump `version` + `url`, optionally set `sha256`/`changelog`) and push it so
   its **raw** URL updates.
4. Point `Config.MANIFEST_URL` at that raw URL (already stubbed to a `dylandoldersum/rs-realm-launcher` path — change it to your repo).

The launcher fetches the manifest on start, compares `version` to `~/.rs-realm/installed.properties`, and
downloads to `~/.rs-realm/fiddled-client.jar` when needed. Client output goes to `~/.rs-realm/client.log`.

## Customising

- `Config.java` — manifest URL, brand name, install paths, client JVM args.
- `Theme.java` — colours/fonts (RuneLite-style dark palette).
- `src/main/resources/logo.png` — 96×96+ logo (falls back to a monogram badge if absent).
