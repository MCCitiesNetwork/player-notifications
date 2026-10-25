/**
 * The host plugin's shading scheme, in one place because **feature modules have to agree with it**.
 *
 * `platform:paper-plugin` relocates its bundled libraries under [BASE]. A feature module compiles
 * against the *unrelocated* coordinates (it sees them transitively, compile-only, through the host
 * project) but at runtime resolves parent-first onto the host class loader, where only the relocated
 * names exist. Any module reference to one of these packages must therefore be rewritten to match,
 * or it fails at runtime — a method whose descriptor mentions a relocated type is simply a different
 * method (`NoSuchMethodError: SqlSessionWrapper.session()`), and a MyBatis `@Select` from the
 * unrelocated package is not the annotation the host's MyBatis looks for.
 *
 * Keep [PACKAGES] as the single source of truth: the `player-notifications-shared-relocation`
 * convention applies it to the host and to every feature module. Adding a shaded library to the host
 * means adding one entry here.
 */
object HostShading {

    /** Relocation target prefix used by `platform:paper-plugin`'s `shadowJar`. */
    const val BASE = "io.github.md5sha256.playernotifications.libraries"

    /** Every package the host relocates. */
    val PACKAGES = listOf(
        "com.minecraftcitiesnetwork.pluginInfrastructure",
        "org.mariadb",
        "org.mybatis",
        "org.apache.ibatis",
        "org.spongepowered",
        "io.leangen.geantyref",
        "com.fasterxml.jackson",
    )
}