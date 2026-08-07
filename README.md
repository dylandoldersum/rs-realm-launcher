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

## Native bundles

Most players do not have Java installed and should not have to. `jpackage` wraps the launcher
together with its own runtime, so the download is one thing that just opens.

```
./gradlew nativeInstaller     # .exe on Windows, .dmg on macOS, .deb on Linux
./gradlew nativeAppImage      # the same app as a plain folder, no installer
```

Output lands in `build/jpackage/installer`. Roughly 77 MB, most of which is the runtime.

That runtime is the **complete** JDK module set rather than a trimmed one, on purpose. It does not
only run the launcher: `GameClient` starts the game client with the same `java.home`, and that
client is RuneLite with a bytecode-injection agent whose module needs are far wider. A module
trimmed here would show up as a client that refuses to start on players' machines while working
perfectly on any developer box that has a system Java.

### How players get a new launcher

They mostly do not have to do anything. The installed app starts
[`Bootstrap`](src/main/java/rs/realm/launcher/Bootstrap.java), not the launcher: it reads
`launcher.properties`, downloads the newer UI jar into `~/.rs-realm/launcher/`, verifies its
checksum and hands over. So a UI change reaches players the next time they open the launcher.

The bundled jar cannot be replaced in place — the JVM holds it open before any of our code runs, and
Windows will not overwrite an open file — which is why the running jar lives outside the install
directory. The installer still ships a copy as a seed, so a first run with no network works, and
every failure path falls back to the newest jar already on disk. A bad upload should cost an update,
not the launcher, because a launcher that will not start cannot be fixed by uploading a better one.

`launcher.properties` is written by the release workflow. Do not edit it by hand.

The downloadable `.jar` behaves the same way. It starts at `Bootstrap` too, finds no sibling to seed
from, and falls back to the launcher classes inside itself. One file that is both the payload the
installed app downloads and a launcher you can run directly — otherwise the direct download would be
the one way of running RS-Realm that never updates, which is a trap rather than a feature.

A **reinstall** is only needed for things outside that jar: a Java version bump (it lives in
`runtime/`), the icon or app name, or `Bootstrap` itself. All rare.

### Releasing

`jpackage` cannot cross-compile — a Windows `.exe` has to be built on Windows and a macOS `.dmg` on
macOS — so releases are built by `.github/workflows/release.yml` on one runner per platform:

```
git tag launcher-v1.0.0
git push origin launcher-v1.0.0
```

That produces three downloads: Windows `.exe`, macOS `.dmg` for Apple Silicon, and the plain `.jar`
for anyone who already has Java 21.

There is no Intel Mac bundle. A bundle carries one architecture's runtime, so Intel would need its
own build on a `macos-13` runner, and GitHub is retiring those — the job sat queued indefinitely and
blocked the release behind it. Intel Mac players use the jar, which needs a system Java 21. If that
ever matters enough, the job is four lines to add back.

The version in the tag is the **launcher's** own, which is not the client version in
`version.properties`. That one is data the launcher downloads and it changes far more often.

### What players will see on first launch

Neither bundle is signed with a paid certificate, so both operating systems warn once.

- **Windows** — SmartScreen shows "Windows protected your PC". *More info* → *Run anyway*. It stops
  once the installer has enough downloads, or immediately with an EV code-signing certificate.
- **macOS** — "unidentified developer". Right-click the app → *Open*, or
  `xattr -cr /Applications/RS-Realm.app`. An Apple Developer ID plus notarisation removes it.

The build does apply an **ad-hoc** signature on macOS. That is not about the warning: Apple Silicon
refuses to execute unsigned arm64 binaries at all, so without it the app would not start rather than
merely warn.

### Icons

`packaging/icons/` holds `icon-source.png` (square art) and the generated `app.ico` and `app.icns`.
They live outside `src/main/resources` on purpose: jpackage reads them at build time and the running
app never does, so under resources they were simply packed into the jar and made the portable
download 3 MB larger. Regenerate after changing the art:

```
powershell -ExecutionPolicy Bypass -File tools\make-icons.ps1
```

The generator writes both containers by hand so the mac icon can be produced on Windows, which is
where this repo is edited. It falls back to `logo.png` if there is no square source — but that logo
is a wordmark sized for a header, and a wordmark shrunk to 32px is an unreadable smudge.

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
