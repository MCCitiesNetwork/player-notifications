plugins {
    `paper-adapter`
    id("com.gradleup.shadow") version "9.3.1"
}

repositories {
    // Legacy DiscordSRV 2.x (com.discordsrv:discordsrv). Compile-only: it is a *link source* only,
    // never a delivery path, so the plugin is optional at runtime.
    maven("https://nexus.scarsz.me/content/groups/public/")
}

dependencies {
    // api, core, plugin-infrastructure and paper-api arrive transitively (compile-only) through the
    // paper-adapter convention's dependency on the host plugin.
    compileOnly("com.discordsrv:discordsrv:1.30.5")

    // Our own JDA, shaded and relocated below. DiscordSRV relocates its bundled JDA to
    // github.scarsz.discordsrv.dependencies.jda.*, so its instance is not type-compatible with
    // upstream net.dv8tion — this module therefore runs its own bot with its own token.
    implementation("net.dv8tion:JDA:6.5.0") {
        // Voice is never used; opus-java pulls native binaries for nothing.
        exclude(module = "opus-java")
    }
    // JDA logs through slf4j; without a binding it prints a fallback warning on every start.
    implementation("org.slf4j:slf4j-jdk14:2.0.18")

    // compileOnlyApi is not on the test runtime classpath, and the sink/factory tests touch
    // Adventure types. See "Testing gotchas" in CLAUDE.md.
    testRuntimeOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    // This module owns its own schema and migrator, so the migrator, the mapper and the link store are
    // tested against a real MariaDB. Like :core:test, this suite therefore NEEDS A RUNNING DOCKER DAEMON;
    // without one it fails rather than skipping.
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.1")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.2")
    // core declares the driver as `implementation`, so it does not reach this module transitively.
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        // Everything bundled is relocated: the module class loader is parent-first onto the host,
        // and the host itself already shades Jackson. An unrelocated copy would collide.
        val base = "io.github.md5sha256.playernotifications.discord.libraries"
        relocate("net.dv8tion", "${base}.net.dv8tion")
        relocate("okhttp3", "${base}.okhttp3")
        relocate("okio", "${base}.okio")
        relocate("kotlin", "${base}.kotlin")
        relocate("org.intellij", "${base}.org.intellij")
        relocate("org.jetbrains.annotations", "${base}.org.jetbrains.annotations")
        relocate("com.fasterxml.jackson", "${base}.com.fasterxml.jackson")
        relocate("org.slf4j", "${base}.org.slf4j")
        relocate("gnu.trove", "${base}.gnu.trove")
        relocate("com.neovisionaries", "${base}.com.neovisionaries")
        relocate("com.google.crypto.tink", "${base}.com.google.crypto.tink")
        relocate("com.google.gson", "${base}.com.google.gson")
        relocate("com.google.protobuf", "${base}.com.google.protobuf")
        relocate("com.google.errorprone", "${base}.com.google.errorprone")
        relocate("com.google.common", "${base}.com.google.common")
        relocate("com.google.thirdparty", "${base}.com.google.thirdparty")
        relocate("org.apache.commons.collections4", "${base}.org.apache.commons.collections4")
        relocate("javax.annotation", "${base}.javax.annotation")
        relocate("org.checkerframework", "${base}.org.checkerframework")

        // Not bundled — rewritten. These libraries live in the host jar under its own relocation
        // prefix, and the module resolves them parent-first from the host class loader, so this
        // module's *references* to MyBatis (its mapper annotations, SqlSessionWrapper#session) and
        // Configurate must name the host's relocated packages or they do not exist at runtime.
        // See HostShading in buildSrc; this must stay in lockstep with the host's shadowJar.
        HostShading.ADAPTER_PACKAGES.forEach { relocate(it, "${HostShading.BASE}.$it") }

        mergeServiceFiles()
    }
}
