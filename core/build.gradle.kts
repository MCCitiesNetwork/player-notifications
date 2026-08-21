plugins {
    `java-library`
    `player-notifications-conventions`
}

dependencies {
    api(projects.api)
    compileOnly("io.papermc.paper:paper-api:26.1.2.+")
    compileOnly("org.jetbrains:annotations:26.0.2-1")
    api("org.spongepowered:configurate-yaml:4.2.0")
    api("org.mybatis:mybatis:3.5.19")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")

    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.1")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.2")
    // compileOnly is not present on the runtime classpath; tests need paper-api's Adventure/Bukkit
    // types (e.g. net.kyori.adventure.text.Component) at test runtime.
    testRuntimeOnly("io.papermc.paper:paper-api:26.1.2.+")
}
