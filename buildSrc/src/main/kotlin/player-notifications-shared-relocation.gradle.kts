// Applied by the host and every feature module. Every package the host shades is relocated here and
// nowhere else, so a module's references always land where the host put the classes — see HostShading.
plugins {
    id("com.gradleup.shadow")
}

val sharedRelocation = extensions.create<SharedRelocationExtension>("sharedRelocation")

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    val bundled = sharedRelocation.bundledByModule.get()
    (HostShading.PACKAGES - bundled).forEach { relocate(it, "${HostShading.BASE}.$it") }
}

/**
 * Packages this project bundles and relocates under its *own* prefix instead of rewriting them to the
 * host's. `discord-adapter` lists Jackson: JDA needs its own copy, not the host's.
 */
abstract class SharedRelocationExtension {
    abstract val bundledByModule: SetProperty<String>

    init {
        bundledByModule.convention(emptySet())
    }
}
