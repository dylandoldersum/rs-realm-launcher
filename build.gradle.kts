plugins {
    application
}

// Pure-JDK launcher — no external dependencies, so the output jar is tiny and needs no
// mavenCentral at build time. Players run it with a Java 21 runtime (`java -jar rs-realm-launcher.jar`).
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
