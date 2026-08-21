plugins {
    `java-library`
    `player-notifications-conventions`
}

dependencies {
    compileOnlyApi("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    // compileOnlyApi is not present on the runtime classpath; tests need paper-api's Adventure/Bukkit
    // types (Component, Dialog, ...) to actually load, not just compile against.
    testRuntimeOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
}
