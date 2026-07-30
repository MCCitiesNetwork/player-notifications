plugins {
    `java-library`
    `player-notifications-conventions`
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.3.1"
}

repositories {
    // plugin-infrastructure is published here (see its README). Use /releases for release versions.
    maven("https://maven.democracycraft.net/snapshots")
}

// Feature-module jars to install into the runServer data folder's modules/ directory. Not transitive:
// an adapter must ship only its own jar, since api/core/paper-api are resolved from the host at runtime.
val featureModules: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // One line per adapter. Adding a new feature module means adding it here too.
    featureModules(project(path = ":platform:essentials-adapter", configuration = "moduleJar"))
    featureModules(project(path = ":platform:discord-adapter", configuration = "moduleJar"))

    api(projects.api)
    api(projects.core)
    api("com.minecraftcitiesnetwork:plugin-infrastructure:1.0.0-SNAPSHOT")
    compileOnlyApi("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}

// Sync, not Copy: a stale jar left behind by a renamed or removed adapter would still be loaded.
val installFeatureModules by tasks.registering(Sync::class) {
    description = "Installs feature-module jars into the runServer data folder's modules/ directory."
    from(featureModules)
    into(layout.projectDirectory.dir("run/plugins/PlayerNotifications/modules"))
    // Modules write their own config into this same directory at runtime — loose files (discord.yml)
    // and per-module subdirectories alike. Sync only owns the jars: preserve everything else, at any
    // depth, so a run never nukes a module's config folder.
    preserve {
        include("**")
        exclude("*.jar")
    }
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        // Defined in buildSrc because feature modules must relocate identically — see HostShading.
        HostShading.PACKAGES.forEach { relocate(it, "${HostShading.BASE}.$it") }
        mergeServiceFiles()
    }

    processResources {
        val projectVersion = version
        filesMatching("paper-plugin.yml") {
            expand("version" to projectVersion)
        }
    }

    runServer {
        minecraftVersion("1.21.8")
        dependsOn(installFeatureModules)

        downloadPlugins {
            // Legacy DiscordSRV 2.x, matching the compileOnly coordinate in platform:discord-adapter.
            // It is the discord adapter's link source (UUID -> Discord id) only — nothing is ever sent
            // through it — but DiscordSrvAccountProvider reports itself unavailable without it, so the
            // adapter cannot be exercised end to end on a server that lacks the plugin.
            github("DiscordSRV", "DiscordSRV", "v1.30.5", "DiscordSRV-Build-1.30.5.jar")

            // EssentialsX, matching the compileOnly coordinate in platform:essentials-adapter.
            // EssentialsMailProcessor resolves the Essentials plugin at runtime, so without this the
            // essentials-mail adapter cannot be exercised end to end.
            github("EssentialsX", "Essentials", "2.21.2", "EssentialsX-2.21.2.jar")
        }
    }
}
