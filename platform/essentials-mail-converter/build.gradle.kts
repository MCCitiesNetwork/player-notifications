plugins {
    `paper-adapter`
}

repositories {
    // EssentialsX mail API.
    maven("https://repo.essentialsx.net/releases/")
}

dependencies {
    // api, core, plugin-infrastructure and paper-api arrive transitively (compile-only) through
    // the paper-adapter convention's dependency on the host plugin. Only the EssentialsX API is
    // adapter-specific, and it is provided by the Essentials plugin at runtime.
    // paper-api is compileOnlyApi on the host, which does not reach the test runtime — without this the
    // converter's tests die on NoClassDefFoundError the moment they touch Adventure. See "Testing
    // gotchas" in CLAUDE.md.
    testRuntimeOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    compileOnly("net.essentialsx:EssentialsX:2.21.2") {
        exclude(group = "org.bukkit", module = "bukkit")
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
}
