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

// Feature modules are not classpath entries: the host loads them from <dataFolder>/modules/ through
// `new URLClassLoader(jarUrl, hostClassLoader)`, and the Discord adapter's relocation strategy depends
// on that isolation. So every adapter publishes its jar as a plain file artifact, which the host's
// `installFeatureModules` task syncs into the runServer data folder.
val moduleJar: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(moduleJar.name, tasks.named<Jar>("jar"))
}

// An adapter that shades (discord-adapter bundles its own relocated JDA) must ship the `-all` jar —
// its plain jar contains no JDA at all. Applied lazily because the adapter's build script applies
// shadow *after* this convention.
plugins.withId("com.gradleup.shadow") {
    moduleJar.outgoing.artifacts.clear()
    artifacts {
        add(moduleJar.name, tasks.named<Jar>("shadowJar"))
    }
}
