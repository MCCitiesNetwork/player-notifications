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

dependencies {
    api(projects.api)
    api(projects.core)
    api("net.democracrycraft:plugin-infrastructure:1.0.0-SNAPSHOT")
    compileOnlyApi("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        val base = "io.github.md5sha256.playernotifications.libraries"
        relocate("org.mariadb", "${base}.org.mariadb")
        relocate("org.mybatis", "${base}.org.mybatis")
        relocate("org.apache.ibatis", "${base}.org.apache.ibatis")
        relocate("org.spongepowered", "${base}.org.spongepowered")
        relocate("io.leangen.geantyref", "${base}.io.leangen.geantyref")
        relocate("com.fasterxml.jackson", "${base}.com.fasterxml.jackson")
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
    }
}
