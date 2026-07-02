plugins {
    `java-library`
    `player-notifications-conventions`
}

dependencies {
    api(project(":api"))
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}
