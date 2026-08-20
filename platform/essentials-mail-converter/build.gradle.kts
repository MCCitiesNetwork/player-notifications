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
    compileOnly("net.essentialsx:EssentialsX:2.21.2") {
        exclude(group = "org.bukkit", module = "bukkit")
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
}
