plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Puts Shadow on every build script's classpath, so player-notifications-shared-relocation can
    // configure shadowJar. Projects therefore apply id("com.gradleup.shadow") without a version.
    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.3.1")
}
