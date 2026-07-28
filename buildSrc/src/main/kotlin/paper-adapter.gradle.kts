plugins {
    `java-library`
    id("player-notifications-conventions")
}

repositories {
    // Resolves plugin-infrastructure, pulled in transitively via the compileOnly host dependency below.
    maven("https://maven.democracycraft.net/snapshots")
}

dependencies {
    // Paper adapter modules (feature modules loaded by the host plugin) compile against the host
    // plugin's classes but must never bundle them — the module class loader resolves them from the
    // host at runtime. Depending on the host jar as compileOnly gives access to the host type
    // (e.g. PlayerNotificationsPlugin) without shipping a second, conflicting copy.
    compileOnly(project(":platform:paper-plugin"))

    // compileOnly does not reach compileTestJava or the test runtime, so an adapter's own tests
    // would not see api/core types at all. Tests never ship, so depending on the host outright is
    // safe here. paper-api itself is compileOnlyApi on the host and still needs a per-module
    // testRuntimeOnly (see "Testing gotchas" in CLAUDE.md).
    testImplementation(project(":platform:paper-plugin"))
}
