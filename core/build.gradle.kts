plugins {
    `java-library`
    `player-notifications-conventions`
}

dependencies {
    api(project(":api"))
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.0.2-1")
    api("org.mybatis:mybatis:3.5.19")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")

    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.1")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.2")
}
