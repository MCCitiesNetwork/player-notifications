plugins {
    `java-library`
    `player-notifications-conventions`
    `player-notifications-publish`
}

dependencies {
    compileOnlyApi("io.papermc.paper:paper-api:26.1.2.+")
    // compileOnlyApi is not present on the runtime classpath; tests need paper-api's Adventure/Bukkit
    // types (Component, Dialog, ...) to actually load, not just compile against.
    testRuntimeOnly("io.papermc.paper:paper-api:26.1.2.+")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "player-notifications-api"
            from(components["java"])
            pom {
                name.set("PlayerNotifications API")
                description.set("Public API for the PlayerNotifications plugin")
            }
        }
    }
}
