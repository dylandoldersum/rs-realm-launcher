plugins {
    application
}

// Pure-JDK launcher — no external dependencies, so the output jar is tiny and needs no
// mavenCentral at build time. Players run it with a Java 21 runtime (`java -jar rs-realm-launcher.jar`),
// or install one of the native bundles produced by `nativeInstaller`, which carry their own.
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

// Compile sources as UTF-8 (Windows javac defaults to windows-1252, which mangles non-ASCII glyphs).
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

application {
    mainClass = "rs.realm.launcher.Launcher"
}

// A runnable jar with the Main-Class baked in (no deps → no shading needed).
tasks.jar {
    archiveBaseName = "rs-realm-launcher"
    archiveVersion = ""
    manifest {
        attributes("Main-Class" to "rs.realm.launcher.Launcher")
    }
}

// ---------------------------------------------------------------------------
// Native bundles
// ---------------------------------------------------------------------------
//
// `jpackage` cannot cross-compile: a Windows .exe has to be built on Windows and a macOS .dmg on
// macOS. The tasks below build whichever bundle fits the machine they run on, and the release
// workflow runs them once per platform.
//
// The version a native installer carries is the LAUNCHER's own, which is not the client version in
// version.properties — that one is data the launcher downloads, and it changes far more often.
val launcherVersion: String = (findProperty("launcherVersion") as String?) ?: "1.0.0"

val appName = "RS-Realm"
val appVendor = "RS-Realm"
val appIdentifier = "com.rsrealm.launcher"

val currentOs: org.gradle.internal.os.OperatingSystem = org.gradle.internal.os.OperatingSystem.current()

// The JDK that owns jlink and jpackage. Taken from the toolchain rather than from PATH, so the
// bundled runtime is the same Java the project compiles against.
val jdkHome: Provider<String> = javaToolchains
    .launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    .map { it.metadata.installationPath.asFile.absolutePath }

fun jdkTool(name: String): String {
    val exe = if (currentOs.isWindows) "$name.exe" else name
    return File(File(jdkHome.get(), "bin"), exe).absolutePath
}

val runtimeDir = layout.buildDirectory.dir("jpackage/runtime")
val appImageDir = layout.buildDirectory.dir("jpackage/image")
val installerDir = layout.buildDirectory.dir("jpackage/installer")
val stagedJarDir = layout.buildDirectory.dir("jpackage/input")

// Everything jpackage consumes has to sit in one directory, and that directory must contain
// NOTHING else — its whole contents are copied into the bundle.
val stageForPackaging by tasks.registering(Copy::class) {
    from(tasks.jar)
    into(stagedJarDir)
}

/**
 * The runtime that ships inside the bundle.
 *
 * Deliberately the COMPLETE JDK module set rather than a jlink-minimised one. This runtime does not
 * only run the launcher: [rs.realm.launcher.GameClient] starts the game client with the same
 * `java.home`, and that client is RuneLite with a bytecode-injection agent. Its module needs are far
 * wider than the launcher's own — instrumentation, attach, scripting, XML, the lot — and a module
 * trimmed here would surface as a client that refuses to start on players' machines while working
 * perfectly on any developer box with a system Java installed.
 *
 * Costs roughly 70 MB in the finished installer. That is the right trade against a class of bug that
 * only ever appears after release.
 */
val bundledRuntime by tasks.registering(Exec::class) {
    dependsOn(tasks.jar)
    outputs.dir(runtimeDir)

    doFirst {
        delete(runtimeDir)
        commandLine(
            jdkTool("jlink"),
            "--module-path", File(jdkHome.get(), "jmods").absolutePath,
            "--add-modules", "ALL-MODULE-PATH",
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress", "zip-6",
            "--output", runtimeDir.get().asFile.absolutePath,
        )
    }
}

fun packageArgs(type: String, destination: File): List<String> {
    // From packaging/, not from src/main/resources/. These are build inputs for jpackage and never
    // read at runtime; under resources they were packed into the jar, where 3 MB of .icns and .ico
    // did nothing but make the portable download bigger.
    val icon = if (currentOs.isWindows) "app.ico" else "app.icns"
    return listOf(
        jdkTool("jpackage"),
        "--type", type,
        "--name", appName,
        "--app-version", launcherVersion,
        "--vendor", appVendor,
        "--input", stagedJarDir.get().asFile.absolutePath,
        "--main-jar", "rs-realm-launcher.jar",
        "--main-class", "rs.realm.launcher.Launcher",
        "--runtime-image", runtimeDir.get().asFile.absolutePath,
        "--icon", file("packaging/icons/$icon").absolutePath,
        "--dest", destination.absolutePath,
    )
}

/**
 * A self-contained application folder, with no installer around it.
 *
 * Useful on its own as a "portable" download, and it is what the macOS job signs before wrapping it
 * in a .dmg.
 */
val nativeAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a self-contained application image for the current platform."
    dependsOn(stageForPackaging, bundledRuntime)
    outputs.dir(appImageDir)

    doFirst {
        delete(appImageDir)
        val args = packageArgs("app-image", appImageDir.get().asFile).toMutableList()
        if (currentOs.isMacOsX) {
            args += listOf("--mac-package-identifier", appIdentifier, "--mac-package-name", appName)
        }
        commandLine(args)
    }
}

/**
 * Puts an ad-hoc signature on the macOS app bundle. No-op anywhere else.
 *
 * Not cosmetic: macOS on Apple Silicon refuses to execute unsigned arm64 binaries outright, so
 * without this the .app does not start at all rather than merely warning. An ad-hoc signature costs
 * nothing and satisfies that rule.
 *
 * It does NOT satisfy Gatekeeper, which wants a Developer ID and notarisation. The first launch
 * still needs right-click → Open, or `xattr -cr` on the installed app. Removing that step means an
 * Apple Developer account; see the release notes in the README.
 */
val macAdHocSign by tasks.registering(Exec::class) {
    onlyIf { currentOs.isMacOsX }
    dependsOn(nativeAppImage)
    // Configured up front so the task is valid even when it is about to be skipped.
    commandLine("true")

    doFirst {
        commandLine(
            "codesign",
            "--force",
            "--deep",
            "--sign", "-",
            File(appImageDir.get().asFile, "$appName.app").absolutePath,
        )
    }
}

/**
 * The installer players download: `.exe` on Windows, `.dmg` on macOS, `.deb` on Linux.
 *
 * Windows installs per-user on purpose. A per-machine install needs an administrator prompt before
 * the launcher has shown anybody anything, which is a poor first impression and, for a game
 * launcher that writes only to the user's own home directory, buys nothing.
 *
 * macOS takes the long way round — app image, sign, then wrap — because a .dmg cannot be signed
 * after the fact. Building straight to .dmg would produce a disk image containing an app that will
 * not start on Apple Silicon.
 */
val nativeInstaller by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a native installer for the current platform (exe / dmg / deb)."
    dependsOn(stageForPackaging, bundledRuntime)
    if (currentOs.isMacOsX) {
        dependsOn(macAdHocSign)
    }
    outputs.dir(installerDir)

    doFirst {
        delete(installerDir)
        val dest = installerDir.get().asFile
        val args = when {
            currentOs.isWindows -> packageArgs("exe", dest) + listOf(
                "--win-shortcut",
                "--win-menu",
                "--win-menu-group", appName,
                "--win-per-user-install",
                "--win-dir-chooser",
            )
            // --app-image replaces the whole "build me one from scratch" argument set, so none of
            // --input / --main-jar / --runtime-image / --icon may appear alongside it.
            currentOs.isMacOsX -> listOf(
                jdkTool("jpackage"),
                "--type", "dmg",
                "--name", appName,
                "--app-version", launcherVersion,
                "--app-image", File(appImageDir.get().asFile, "$appName.app").absolutePath,
                "--dest", dest.absolutePath,
            )
            else -> packageArgs("deb", dest) + listOf("--linux-shortcut")
        }
        commandLine(args)
    }
}
