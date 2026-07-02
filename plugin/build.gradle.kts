plugins {
    `java-library`
    `player-notifications-conventions`
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.3.1"
}

dependencies {
    api(project(":api"))
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}

tasks {
    build {
        dependsOn(shadowJar)
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
