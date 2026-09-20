package net.aechronis.server.modules

/** Immutable metadata; contains no module objects or classloaders. */
data class ModuleSource(
    val id: String,
    val fingerprint: String,
    val artifact: String,
)

/** Read-only access to runtime module profiling metadata. */
object ModuleDiagnostics {
    fun sources(): List<ModuleSource> = ModuleRuntime.diagnosticSources()

    fun sourceOf(type: Class<*>): ModuleSource? = ModuleRuntime.diagnosticSource(type)

    /** Resolves without class initialization, and never loads from an already closed generation. */
    fun findClass(name: String): Class<*>? = ModuleRuntime.diagnosticClass(name)
}
