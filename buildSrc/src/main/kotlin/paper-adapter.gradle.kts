plugins {
    `java-library`
    id("player-notifications-conventions")
    id("com.gradleup.shadow")
    id("player-notifications-shared-relocation")
}

repositories {
    // Resolves plugin-infrastructure, pulled in transitively via the compileOnly host dependency below.
    maven("https://maven.minecraftcitiesnetwork.com/snapshots")
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

// Feature modules are not classpath entries: the host loads them from <dataFolder>/modules/ through
// `new URLClassLoader(jarUrl, hostClassLoader)`, and the Discord adapter's relocation strategy depends
// on that isolation. So every adapter publishes its jar as a plain file artifact, which the host's
// `installFeatureModules` task syncs into the runServer data folder.
val moduleJar: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

// Every module ships its shadow jar, even one that bundles nothing: its own classes extend
// plugin-infrastructure's SimplePluginModule/PluginModule, which exist at runtime only under the host's
// relocated names, so a plain jar names classes that do not exist.
artifacts {
    add(moduleJar.name, tasks.named<Jar>("shadowJar"))
}

tasks.named("build") {
    dependsOn("shadowJar")
}
