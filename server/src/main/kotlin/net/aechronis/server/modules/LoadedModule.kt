package net.aechronis.server.modules

import net.aechronis.server.resourcepack.ModuleResourcePacks
import net.aechronis.server.resourcepack.ResourcePackRegistration
import java.util.concurrent.CompletableFuture

/** Lifecycle and registrations for exactly one classloader. Runtime mutations belong to the lifecycle worker. */
internal class LoadedModule(
    val artifact: ModuleArtifact,
    dependencies: List<LoadedModule>,
) : AutoCloseable {
    val classLoader: ModuleClassLoader = ModuleClassLoader(artifact.jar.toUri().toURL(), dependencies.map { it.classLoader })
    val module: AechronisModule =
        try {
            withContextClassLoader(classLoader) {
                classLoader.loadClass(artifact.definition.provider).getDeclaredConstructor().newInstance() as AechronisModule
            }.also {
                check(it.javaClass.classLoader === classLoader) { "Module provider was supplied by a dependency" }
                check(
                    it.id == artifact.definition.id &&
                        it.dependencies == artifact.definition.dependencies &&
                        it.reloadTogether == artifact.definition.reloadTogether,
                ) {
                    "Module metadata changed between discovery and loading"
                }
            }
        } catch (error: Throwable) {
            classLoader.close()
            throw error
        }
    private val scope = ModuleResourceScope(classLoader).apply { published = false }
    private val registrations = RuntimeRegistrations(scope)
    private val checkpointTracker = ModuleCheckpointTracker()
    private var preparedPack: ModuleResourcePacks? = null
    private var installedPack: ResourcePackRegistration? = null
    private var activated = false
    private var initialized = false
    private var prepared = false
    var running = false
        private set

    fun prepareResources(context: ModuleContext) {
        preparedPack = withContextClassLoader(classLoader) { context.prepareResourcePacks(artifact, module) }
    }

    fun configure(context: ModuleContext) {
        ModuleRuntime.activate(scope)
        activated = true
        withContextClassLoader(classLoader) { registrations.record { module.configure(context) } }
    }

    fun start(context: ModuleContext) {
        preparedPack?.let { installedPack = context.installResourcePacks(it) }
        preparedPack = null
        initialized = true
        withContextClassLoader(classLoader) { registrations.record { module.initialize(context) } }
        running = true
    }

    fun publish() {
        scope.published = true
    }

    fun detachInputs() {
        scope.rejectCallbacks()
        registrations.detachInputs()
        check(scope.cancelTasks().isEmpty()) { "Failed to cancel tasks for ${module.id}" }
    }

    fun quiesce() {
        scope.quiesceEvents()
        checkpointTracker.awaitQuiescence()
    }

    fun prepareForShutdown(context: ModuleContext) {
        if (!activated || prepared) return
        quiesce()
        if (initialized) withContextClassLoader(classLoader) { module.prepareForShutdown(context) }
        prepared = true
    }

    fun saveState(context: ModuleContext) {
        if (!running) return
        withContextClassLoader(classLoader) { module.saveState(context) }
        checkpointTracker.settleAfterAuthoritativeSave()
    }

    fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> =
        checkpointTracker.request { withContextClassLoader(classLoader) { module.saveCheckpoint(context) } }

    fun stop(context: ModuleContext) {
        if (!activated) return
        detachInputs()
        prepareForShutdown(context)
        if (initialized) {
            withContextClassLoader(classLoader) { module.shutdown(context) }
            initialized = false
        }
        installedPack?.close()
        installedPack = null
        registrations.cleanup()
        ModuleRuntime.deactivate(scope)
        activated = false
        running = false
    }

    override fun close() {
        check(!activated) { "Refusing to close an active or unsafe module classloader: ${module.id}" }
        preparedPack?.hostedPack?.retire()
        preparedPack = null
        classLoader.close()
    }
}
