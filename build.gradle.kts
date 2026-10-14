plugins {
    java
}

group = "dev.ryvrook"
version = "1.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Which Hytale server artifact to compile against. Defaults live in gradle.properties
// and can be overridden with -Phytale_channel=release -Phytale_version=0.7.0
val hytaleChannel = (project.findProperty("hytale_channel") as String?)?.trim() ?: "release"
val hytaleVersion = (project.findProperty("hytale_version") as String?)?.trim() ?: "0.7.1"
require(hytaleChannel == "release" || hytaleChannel == "pre-release") {
    "hytale_channel must be \"release\" or \"pre-release\", got \"$hytaleChannel\""
}

repositories {
    mavenCentral()
    maven {
        name = "hytale-$hytaleChannel"
        url = uri("https://maven.hytale.com/$hytaleChannel")
    }
}

dependencies {
    // Provided by the Hytale server at runtime, never bundled into the mod jar.
    compileOnly("com.hypixel.hytale:Server:$hytaleVersion")

    // Tests run without a server. The server jar is on the test classpath for Gson, the logger and engine
    // marker types only. No test ever starts an engine.
    testImplementation("com.hypixel.hytale:Server:$hytaleVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The benchmark prints its numbers, run with -Pbench to see them in the console.
    testLogging {
        showStandardStreams = project.hasProperty("bench")
        events("failed")
    }
    systemProperty("trackerapi.bench", project.hasProperty("bench").toString())
    // The engine logger insists on owning java.util.logging before anything logs.
    systemProperty("java.util.logging.manager", "com.hypixel.hytale.logger.backend.HytaleLogManager")
    jvmArgs("-Xmx1G")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// The version above is the only place it is set. The manifest gets it through processResources.
val versionText = project.version.toString()
tasks.processResources {
    inputs.property("version", versionText)
    filesMatching("manifest.json") {
        filter { line -> line.replace("@version@", versionText) }
    }
}

tasks.jar {
    archiveBaseName.set("TrackerAPI")
    archiveVersion.set(project.version.toString())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

/**
 * Copies the built jar into the local Hytale UserData/Mods folder so it loads in
 * singleplayer worlds. Override the target with -PmodsDir=/path/to/Mods.
 */
val installMod by tasks.registering(Copy::class) {
    group = "hytale"
    description = "Builds the mod and copies it into the local Hytale UserData/Mods folder."
    dependsOn(tasks.jar)

    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val defaultMods = when {
        os.contains("mac") -> "$home/Library/Application Support/Hytale/UserData/Mods"
        os.contains("win") -> (System.getenv("APPDATA") ?: "$home/AppData/Roaming") + "/Hytale/UserData/Mods"
        else -> "$home/.local/share/Hytale/UserData/Mods"
    }
    val target = (project.findProperty("modsDir") as String?) ?: defaultMods

    from(tasks.jar.flatMap { it.archiveFile })
    into(target)
    doLast { println("Installed ${tasks.jar.get().archiveFileName.get()} -> $target") }
}
