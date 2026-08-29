package net.aechronis.server.modules

import net.aechronis.server.resourcepack.ResourcePackRegistration
import net.minestom.server.MinecraftServer
import net.minestom.server.network.packet.server.CachedPacket
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ServiceLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Owns the complete runtime module graph.
 *
 * Modules intentionally share one classloader so they can depend on each other normally. JVM
 * classes can only be unloaded with their classloader, therefore every runtime operation stages,
 * validates and replaces the complete generation even when only one module is enabled or disabled.
 */
class ModuleManager private constructor(
    private val directory: Path,
) : AutoCloseable,
    ModuleAdministration {
    private val operationLock = Any()
    private val disabledFile = directory.resolve(DISABLED_FILE_NAME)
    private val requiredModules = readModuleIds(directory.resolve(REQUIRED_FILE_NAME), "required module")

    private var current: Generation? = null
    private var context: ModuleContext? = null
    private var generationNumber = 0L

    @Volatile
    private var phase = ManagerPhase.STARTING
    private val checkpointTracker = ModuleCheckpointTracker()

    @Volatile
    private var publicSnapshot = ModuleSnapshot(0, phase.displayName, emptyList())

    fun initialize(context: ModuleContext) {
        synchronized(operationLock) {
            check(this.context == null) { "Modules have already been initialized" }
            val generation = checkNotNull(current) { "No staged module generation exists" }
            this.context = context
            phase = ManagerPhase.OPERATING
            updateSnapshot()
            try {
                generation.start(context)
                generationNumber = 1
                phase = ManagerPhase.RUNNING
                updateSnapshot()
                refreshClients()
            } catch (error: Throwable) {
                report("Failed to initialize the module generation", error)
                val stopFailures = generation.stop(context)
                stopFailures.forEach { System.err.println("[Modules] $it") }
                generation.close()
                current = generation.takeIf { stopFailures.isNotEmpty() }
                phase = ManagerPhase.DEGRADED
                updateSnapshot()
                throw error
            }
        }
    }

    override fun snapshot(): ModuleSnapshot = publicSnapshot

    internal fun isAcceptingPlayers(): Boolean = phase == ManagerPhase.RUNNING

    override fun enable(id: String): ModuleOperationResult =
        transition("Enable '$id'") { old, candidate ->
            val module = candidate.availableById[id] ?: return@transition Decision.reject("Unknown module '$id'")
            if (id in old.activeIds) return@transition Decision.reject("Module '$id' is already enabled")
            val dependencies = dependencyClosure(module.module, candidate.availableById)
            val disabled = old.explicitDisabled - dependencies
            candidate.plan(disabled)
            if (id !in candidate.activeIds) {
                Decision.reject("Module '$id' cannot be enabled: ${candidate.disabledReasons[id] ?: "dependency graph is unavailable"}")
            } else {
                Decision.accept(disabled)
            }
        }

    override fun disable(
        id: String,
        cascade: Boolean,
    ): ModuleOperationResult =
        transition("Disable '$id'") { old, _ ->
            if (id !in old.availableById) return@transition Decision.reject("Unknown module '$id'")
            if (id !in old.activeIds) return@transition Decision.reject("Module '$id' is already disabled")

            val dependants = dependantClosure(id, old.availableById).filterTo(linkedSetOf()) { it in old.activeIds }
            if (dependants.isNotEmpty() && !cascade) {
                return@transition Decision.reject(
                    "Module '$id' is required by ${dependants.sorted().joinToString()}; retry with cascade",
                )
            }
            val disabled = linkedSetOf(id).apply { if (cascade) addAll(dependants) }
            Decision.accept(old.explicitDisabled + disabled)
        }

    override fun restart(id: String): ModuleOperationResult =
        transition("Restart '$id'") { old, candidate ->
            if (id !in old.availableById) {
                Decision.reject("Unknown module '$id'")
            } else if (id !in old.activeIds) {
                Decision.reject("Module '$id' is disabled; enable it instead")
            } else if (id !in candidate.activeIds) {
                Decision.reject(
                    "Module '$id' cannot be restarted from the staged JARs: " +
                        (candidate.disabledReasons[id] ?: "its JAR is no longer present"),
                )
            } else {
                Decision.accept(old.explicitDisabled)
            }
        }

    override fun reload(): ModuleOperationResult =
        transition("Reload modules") { old, _ ->
            Decision.accept(old.explicitDisabled)
        }

    /** Prevents new runtime transitions from entering once coordinated shutdown has begun. */
    fun beginShutdown() {
        synchronized(operationLock) {
            if (phase == ManagerPhase.RUNNING) {
                phase = ManagerPhase.CLOSING
                updateSnapshot()
            }
        }
    }

    fun saveState(context: ModuleContext) {
        synchronized(operationLock) {
            val generation = current ?: return
            generation.saveState(context)
            checkpointTracker.settleAfterAuthoritativeSave()
        }
    }

    /** Quiesces mutable module work before coordinated shutdown persists module and world state. */
    fun prepareForShutdown(context: ModuleContext) {
        synchronized(operationLock) {
            phase = ManagerPhase.CLOSING
            updateSnapshot()
            val generation = current ?: return
            val failures = generation.prepareForShutdown(context)
            check(failures.isEmpty()) { failures.joinToString(separator = "; ") }
            awaitCheckpointQuiescence()
        }
    }

    fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> =
        synchronized(operationLock) {
            if (phase != ManagerPhase.RUNNING) {
                return@synchronized CompletableFuture.failedFuture(
                    IllegalStateException("Module manager is ${phase.displayName}; periodic checkpoint skipped"),
                )
            }
            checkpointTracker.request {
                current?.saveCheckpoint(context) ?: CompletableFuture.completedFuture<Void>(null)
            }
        }

    override fun close() {
        synchronized(operationLock) {
            phase = ManagerPhase.CLOSING
            updateSnapshot()
            val generation = current
            val completedCheckpointFailure =
                runCatching(::awaitCheckpoints).exceptionOrNull()?.also { error ->
                    if (checkpointTracker.hasPending()) throw error
                    report("A completed checkpoint failed before module teardown", error)
                }
            var teardownFailure: Throwable? = null
            try {
                if (generation != null) {
                    val moduleContext = context
                    if (moduleContext != null) {
                        val stopFailures = generation.stop(moduleContext)
                        stopFailures.forEach { System.err.println("[Modules] $it") }
                        if (stopFailures.isNotEmpty()) {
                            teardownFailure =
                                IllegalStateException(
                                    "Module generation teardown failed: ${stopFailures.joinToString(separator = "; ")}",
                                )
                        }
                    } else {
                        check(!generation.started) { "A started module generation has no module context" }
                    }
                }
            } catch (error: Throwable) {
                teardownFailure = error
            } finally {
                generation?.close()
                if (teardownFailure == null) current = null
                updateSnapshot()
            }
            val failure = completedCheckpointFailure
            teardownFailure?.let { error ->
                failure?.addSuppressed(error) ?: throw error
            }
            failure?.let { throw it }
        }
    }

    private fun transition(
        description: String,
        decide: (old: Generation, staged: Generation) -> Decision,
    ): ModuleOperationResult =
        synchronized(operationLock) {
            if (phase != ManagerPhase.RUNNING) {
                return@synchronized ModuleOperationResult(false, "Module manager is ${phase.displayName}")
            }
            val moduleContext = context ?: return@synchronized ModuleOperationResult(false, "Modules are not initialized")
            val old = current ?: return@synchronized ModuleOperationResult(false, "No module generation is active")

            phase = ManagerPhase.OPERATING
            updateSnapshot()
            val staged =
                try {
                    stageGeneration(moduleJarsForGeneration(directory), old.explicitDisabled)
                } catch (error: Throwable) {
                    phase = ManagerPhase.RUNNING
                    updateSnapshot()
                    report("$description failed while staging JARs", error)
                    return@synchronized ModuleOperationResult(
                        false,
                        "$description failed before the running modules were changed: ${failureMessage(error)}",
                    )
                }

            val decision =
                try {
                    decide(old, staged)
                } catch (error: Throwable) {
                    staged.close()
                    phase = ManagerPhase.RUNNING
                    updateSnapshot()
                    report("$description failed while resolving the staged graph", error)
                    return@synchronized ModuleOperationResult(
                        false,
                        "$description failed before the running modules were changed: ${failureMessage(error)}",
                    )
                }
            if (!decision.accepted) {
                staged.close()
                phase = ManagerPhase.RUNNING
                updateSnapshot()
                return@synchronized ModuleOperationResult(false, decision.rejection!!)
            }

            try {
                staged.plan(decision.disabled)
                validateRequiredModules(staged)
            } catch (error: Throwable) {
                staged.close()
                phase = ManagerPhase.RUNNING
                updateSnapshot()
                report("$description found an invalid staged dependency graph", error)
                return@synchronized ModuleOperationResult(
                    false,
                    "$description failed before the running modules were changed: ${failureMessage(error)}",
                )
            }

            val affected = old.activeIds + staged.activeIds
            val preparationFailures = old.prepareForShutdown(moduleContext)
            if (preparationFailures.isNotEmpty()) {
                return@synchronized failPreparedGeneration(
                    description = description,
                    failure = IllegalStateException(preparationFailures.joinToString(separator = "; ")),
                    staged = staged,
                    old = old,
                    context = moduleContext,
                    affected = affected,
                    stage = "quiescing the active generation",
                )
            }

            try {
                awaitCheckpointQuiescence()
                old.saveState(moduleContext)
                checkpointTracker.settleAfterAuthoritativeSave()
                moduleContext.saveCoreWorld()
            } catch (error: Throwable) {
                return@synchronized failPreparedGeneration(
                    description = description,
                    failure = error,
                    staged = staged,
                    old = old,
                    context = moduleContext,
                    affected = affected,
                    stage = "saving the active generation",
                )
            }

            val stopFailures = old.stop(moduleContext)
            stopFailures.forEach { System.err.println("[Modules] $it") }
            if (stopFailures.isNotEmpty()) {
                staged.close()
                old.close()
                current = old
                phase = ManagerPhase.DEGRADED
                updateSnapshot()
                moduleContext.shutdownServer()
                return@synchronized ModuleOperationResult(
                    success = false,
                    message = "$description failed because the active generation could not be cleaned up; the server is shutting down",
                    affectedIds = affected,
                )
            }

            try {
                staged.start(moduleContext)
                writeDisabled(staged.explicitDisabled)
            } catch (candidateError: Throwable) {
                report("$description failed while starting the staged generation", candidateError)
                val candidateStopFailures =
                    runCatching { staged.stop(moduleContext) }
                        .getOrElse { cleanupError ->
                            report("Failed to clean the staged generation after '$description'", cleanupError)
                            listOf("Failed to clean the staged generation: ${failureMessage(cleanupError)}")
                        }
                candidateStopFailures.forEach { System.err.println("[Modules] $it") }
                staged.close()

                if (candidateStopFailures.isNotEmpty()) {
                    runCatching { writeDisabled(old.explicitDisabled) }
                        .onFailure { report("Failed to restore the disabled-module state after candidate cleanup failure", it) }
                    old.close()
                    current = staged
                    phase = ManagerPhase.DEGRADED
                    updateSnapshot()
                    moduleContext.shutdownServer()
                    return@synchronized ModuleOperationResult(
                        success = false,
                        message = "$description failed and the staged generation could not be cleaned up; the server is shutting down",
                        affectedIds = affected,
                    )
                }

                var rollback: Generation? = null
                try {
                    rollback = stageGeneration(old.stagedJars, old.explicitDisabled)
                    rollback.start(moduleContext)
                } catch (rollbackError: Throwable) {
                    val rollbackStopFailures =
                        runCatching { rollback?.stop(moduleContext).orEmpty() }
                            .getOrElse { cleanupError ->
                                report("Failed to clean the rollback generation after '$description'", cleanupError)
                                listOf("Failed to clean the rollback generation: ${failureMessage(cleanupError)}")
                            }
                    rollbackStopFailures.forEach { System.err.println("[Modules] $it") }
                    runCatching { rollback?.close() }
                    report("Rollback after '$description' failed", rollbackError)
                    old.close()
                    current = rollback.takeIf { rollbackStopFailures.isNotEmpty() }
                    phase = ManagerPhase.DEGRADED
                    updateSnapshot()
                    moduleContext.shutdownServer()
                    return@synchronized ModuleOperationResult(
                        false,
                        "$description failed and the previous module generation could not be restored; the server is shutting down",
                    )
                }

                runCatching { writeDisabled(old.explicitDisabled) }
                    .onFailure { report("Failed to restore the disabled-module state after rollback", it) }
                old.close()
                current = checkNotNull(rollback)
                generationNumber += 1
                phase = ManagerPhase.RUNNING
                updateSnapshot()
                refreshClients()
                return@synchronized ModuleOperationResult(
                    success = false,
                    message = "$description failed; the previous generation was restored: ${failureMessage(candidateError)}",
                    affectedIds = affected,
                    rolledBack = true,
                )
            }

            old.close()
            current = staged
            generationNumber += 1
            phase = ManagerPhase.RUNNING
            updateSnapshot()
            refreshClients()

            ModuleOperationResult(
                success = true,
                message = "$description completed by replacing the complete module generation",
                affectedIds = affected,
            )
        }

    private fun awaitCheckpoints() {
        checkpointTracker.awaitCompletion()
    }

    private fun awaitCheckpointQuiescence() {
        checkpointTracker.awaitQuiescence()
    }

    private fun failPreparedGeneration(
        description: String,
        failure: Throwable,
        staged: Generation,
        old: Generation,
        context: ModuleContext,
        affected: Set<String>,
        stage: String,
    ): ModuleOperationResult {
        report("$description failed while $stage", failure)
        staged.close()
        // Keep the prepared generation intact for the coordinated shutdown pass. That pass can
        // retry quiescence and authoritative saves in order; tearing it down here would lose the
        // only safe opportunity to replace a failed periodic checkpoint.
        current = old
        phase = ManagerPhase.DEGRADED
        updateSnapshot()
        context.shutdownServer()
        return ModuleOperationResult(
            success = false,
            message =
                "$description failed while $stage; the prepared generation cannot safely resume, so the server is shutting down: " +
                    failureMessage(failure),
            affectedIds = affected,
        )
    }

    private fun updateSnapshot() {
        val generation = current
        val modules =
            generation?.available?.map { available ->
                val module = available.module
                ModuleStatus(
                    id = module.id,
                    enabled = module.id in generation.activeIds && generation.started,
                    dependencies = module.dependencies.toSortedSet(),
                    dependants = directDependants(module.id, generation.availableById).toSortedSet(),
                    sourceJar = available.sourceJar,
                    reason = generation.disabledReasons[module.id],
                )
            } ?: emptyList()
        publicSnapshot = ModuleSnapshot(generationNumber, phase.displayName, modules)
    }

    private fun refreshClients() {
        val recipeManager = MinecraftServer.getRecipeManager()
        (recipeManager.declareRecipesPacket as? CachedPacket)?.invalidate()
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            runCatching(player::refreshCommands)
            runCatching(player::refreshRecipes)
        }
    }

    private fun writeDisabled(disabled: Set<String>) {
        Files.createDirectories(directory)
        val temporary = directory.resolve(".$DISABLED_FILE_NAME-${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, disabled.sorted().joinToString(separator = "\n", postfix = if (disabled.isEmpty()) "" else "\n"))
        try {
            Files.move(temporary, disabledFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, disabledFile, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun validateRequiredModules(generation: Generation) {
        val missing = requiredModules - generation.availableById.keys
        require(missing.isEmpty()) {
            "Required module JARs are missing: ${missing.sorted().joinToString()}. Repair the deployment or its $REQUIRED_FILE_NAME file."
        }
        val unavailable = requiredModules.filter { it !in generation.explicitDisabled && it !in generation.activeIds }
        require(unavailable.isEmpty()) {
            "Required modules are unavailable: ${unavailable.sorted().joinToString()}. " +
                "Explicitly disable them or repair their dependency graph."
        }
    }

    private class Generation(
        val stagingDirectory: Path,
        val stagedJars: List<Path>,
        val classLoader: URLClassLoader,
        val available: List<AvailableModule>,
        disabled: Set<String>,
    ) : AutoCloseable {
        val availableById = available.associateBy { it.module.id }

        var explicitDisabled: Set<String> = emptySet()
            private set
        var disabledReasons: Map<String, String> = emptyMap()
            private set
        var active: List<AvailableModule> = emptyList()
            private set
        val activeIds: Set<String>
            get() = active.mapTo(linkedSetOf()) { it.module.id }

        var started: Boolean = false
            private set
        private var scope: ModuleResourceScope? = null
        private var registrations: RuntimeRegistrations? = null
        private val resourcePackRegistrations = mutableListOf<ResourcePackRegistration>()
        private val initialized = mutableListOf<AvailableModule>()
        private val prepared = mutableSetOf<AvailableModule>()
        private var inventoriesClosed = false
        private var inputsDetached = false
        private var tasksCancelled = false
        private var callbacksQuiesced = false
        private var closed = false
        private var closeSafe = true
        private var startComplete = false

        init {
            plan(disabled)
        }

        fun plan(disabled: Set<String>) {
            check(!started) { "Cannot re-plan a started generation" }
            explicitDisabled = disabled.toSet()
            val reasons = linkedMapOf<String, String>()
            available.forEach { available ->
                if (available.module.id in explicitDisabled) reasons[available.module.id] = "disabled by administrator"
            }

            var changed: Boolean
            do {
                changed = false
                available.forEach { available ->
                    val module = available.module
                    if (module.id in reasons) return@forEach
                    val unavailable = module.dependencies.firstOrNull { it !in availableById || it in reasons }
                    if (unavailable != null) {
                        reasons[module.id] =
                            if (unavailable !in availableById) {
                                "missing dependency '$unavailable'"
                            } else {
                                "dependency '$unavailable' is disabled"
                            }
                        changed = true
                    }
                }
            } while (changed)

            disabledReasons = reasons
            val enabled = available.filterNot { it.module.id in reasons }
            val enabledIds = enabled.mapTo(hashSetOf()) { it.module.id }
            enabled.forEach { available ->
                val conflict = available.module.conflicts.firstOrNull { it in enabledIds }
                require(conflict == null) {
                    "Module '${available.module.id}' conflicts with enabled module '$conflict'"
                }
            }
            val sorted = sort(enabled.map { it.module })
            val records = enabled.associateBy { it.module.id }
            active = sorted.map { records.getValue(it.id) }
        }

        fun start(context: ModuleContext) {
            check(!started) { "Module generation is already started" }
            val resourceScope = ModuleResourceScope(classLoader)
            scope = resourceScope
            registrations = RuntimeRegistrations.capture(classLoader)
            ModuleRuntime.activate(resourceScope)
            started = true
            active.asReversed().forEach { available ->
                withContextClassLoader(classLoader) {
                    available.module.configure(context)
                }
            }
            active.forEach { available ->
                initialized += available
                withContextClassLoader(classLoader) {
                    context.installResourcePacks(
                        id = available.module.id,
                        owner = available.module.javaClass,
                        externalPacks = available.module.externalResourcePacks,
                    )
                }?.let(resourcePackRegistrations::add)
                withContextClassLoader(classLoader) {
                    available.module.initialize(context)
                }
                checkNotNull(registrations).instrumentCallbacks(resourceScope)
                println("[Modules] Loaded ${available.module.id} from ${available.sourceJar}")
            }
            context.sendResourcePacksToOnlinePlayers()
            startComplete = true
        }

        fun saveState(context: ModuleContext) {
            if (!startComplete) return
            initialized.asReversed().toList().forEach { available ->
                withContextClassLoader(classLoader) { available.module.saveState(context) }
            }
        }

        fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> {
            val checkpoints =
                initialized.asReversed().map { available ->
                    try {
                        val checkpoint: CompletableFuture<Void>? =
                            withContextClassLoader(classLoader) {
                                available.module.saveCheckpoint(context)
                            }
                        checkpoint
                            ?: CompletableFuture.failedFuture(
                                NullPointerException("Module '${available.module.id}' returned null from saveCheckpoint"),
                            )
                    } catch (error: Throwable) {
                        CompletableFuture.failedFuture(error)
                    }
                }
            return CompletableFuture.allOf(*checkpoints.toTypedArray())
        }

        fun prepareForShutdown(context: ModuleContext): List<String> {
            val failures = mutableListOf<String>()
            if (!inventoriesClosed) {
                // Let the old generation settle inventory state while its listeners are attached,
                // before module/player snapshots are captured.
                MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
                    if (player.openInventory != null) {
                        runCatching(player::closeInventory).onFailure { error ->
                            failures += "Failed to close ${player.username}'s module inventory: ${failureMessage(error)}"
                        }
                    }
                }
                if (failures.isNotEmpty()) return failures
                inventoriesClosed = true
            }
            if (!inputsDetached) {
                registrations?.let { registration ->
                    runCatching(registration::detachInputs)
                        .onFailure { failures += "Failed to detach module inputs: ${failureMessage(it)}" }
                }
                if (failures.isNotEmpty()) return failures
                inputsDetached = true
            }
            if (!tasksCancelled) {
                scope?.cancelTasks()?.forEach { failure -> failures += "Failed to cancel a module task: $failure" }
                if (failures.isNotEmpty()) return failures
                tasksCancelled = true
            }
            if (!callbacksQuiesced) {
                scope?.let { resourceScope ->
                    runCatching(resourceScope::quiesceEvents)
                        .onFailure { failures += "Failed to quiesce module callbacks: ${failureMessage(it)}" }
                }
                if (failures.isNotEmpty()) return failures
                callbacksQuiesced = true
            }
            initialized.asReversed().forEach { available ->
                if (available in prepared) return@forEach
                runCatching {
                    withContextClassLoader(classLoader) { available.module.prepareForShutdown(context) }
                }.onSuccess {
                    prepared += available
                }.onFailure { error ->
                    failures += "Failed to quiesce ${available.module.id}: ${failureMessage(error)}"
                    report("Failed to quiesce ${available.module.id}", error)
                }
            }
            return failures
        }

        fun stop(context: ModuleContext): List<String> {
            if (
                !started &&
                initialized.isEmpty() &&
                scope == null &&
                registrations == null &&
                resourcePackRegistrations.isEmpty()
            ) {
                return emptyList()
            }
            // A bounded quiescence failure may be retried during coordinated shutdown after the
            // in-flight callback finishes. A successful retry makes teardown safe again.
            closeSafe = true
            val failures = prepareForShutdown(context).toMutableList()
            if (failures.isNotEmpty()) {
                closeSafe = false
                return failures
            }

            initialized.asReversed().toList().forEach { available ->
                runCatching {
                    withContextClassLoader(classLoader) { available.module.shutdown(context) }
                }.onSuccess {
                    initialized.remove(available)
                }.onFailure { error ->
                    failures += "Failed to stop ${available.module.id}: ${failureMessage(error)}"
                    closeSafe = false
                    report("Failed to stop ${available.module.id}", error)
                }
            }
            if (failures.isNotEmpty()) return failures
            runCatching(context::removeResourcePacksFromOnlinePlayers)
                .onFailure {
                    closeSafe = false
                    failures += "Failed to remove module resource packs: ${failureMessage(it)}"
                }
            if (failures.isNotEmpty()) return failures
            resourcePackRegistrations.asReversed().toList().forEach { registration ->
                runCatching(registration::close)
                    .onSuccess { resourcePackRegistrations.remove(registration) }
                    .onFailure {
                        closeSafe = false
                        failures += "Failed to stop a module resource pack: ${failureMessage(it)}"
                    }
            }
            if (failures.isNotEmpty()) return failures
            registrations?.let { registration ->
                runCatching(registration::cleanup)
                    .onSuccess { registrations = null }
                    .onFailure {
                        closeSafe = false
                        failures += "Failed to clean module registrations: ${failureMessage(it)}"
                    }
            }
            if (failures.isNotEmpty()) return failures
            scope?.let { resourceScope ->
                runCatching { ModuleRuntime.deactivate(resourceScope) }
                    .onSuccess { scope = null }
                    .onFailure {
                        closeSafe = false
                        failures += "Failed to detach the module runtime scope: ${failureMessage(it)}"
                    }
            }
            if (failures.isNotEmpty()) return failures
            prepared.clear()
            inventoriesClosed = false
            inputsDetached = false
            tasksCancelled = false
            callbacksQuiesced = false
            started = false
            startComplete = false
            return failures
        }

        override fun close() {
            if (closed) return
            if (!closeSafe || started || initialized.isNotEmpty()) {
                System.err.println("[Modules] Retaining an unsafe module classloader until process exit")
                return
            }
            closed = true
            runCatching(classLoader::close)
                .onFailure { report("Failed to close a module classloader", it) }
            deleteTree(stagingDirectory)
        }
    }

    private data class AvailableModule(
        val module: AechronisModule,
        val sourceJar: String,
    )

    private data class Decision(
        val accepted: Boolean,
        val disabled: Set<String>,
        val rejection: String?,
    ) {
        companion object {
            fun accept(disabled: Set<String>) = Decision(true, disabled, null)

            fun reject(message: String) = Decision(false, emptySet(), message)
        }
    }

    private enum class ManagerPhase(
        val displayName: String,
    ) {
        STARTING("starting"),
        RUNNING("running"),
        OPERATING("operating"),
        CLOSING("closing"),
        DEGRADED("degraded"),
    }

    companion object {
        private const val DISABLED_FILE_NAME = ".disabled-modules"
        private const val REQUIRED_FILE_NAME = ".required-modules"
        private val MODULE_ID = Regex("[a-z0-9][a-z0-9_-]*")

        fun discover(directory: Path): ModuleManager {
            Files.createDirectories(directory)
            val manager = ModuleManager(directory)
            val disabled = manager.readDisabled()
            val jars = moduleJarsForGeneration(directory)
            val generation = stageGeneration(jars, disabled)
            try {
                manager.validateRequiredModules(generation)
            } catch (error: Throwable) {
                generation.close()
                throw error
            }
            manager.current = generation
            manager.updateSnapshot()
            return manager
        }

        internal fun sort(modules: List<AechronisModule>): List<AechronisModule> {
            val byId = linkedMapOf<String, AechronisModule>()
            modules.forEach { module ->
                validateIds(module)
                require(byId.put(module.id, module) == null) { "Duplicate module id '${module.id}'" }
            }

            byId.values.forEach { module ->
                val missing = module.dependencies - byId.keys
                require(missing.isEmpty()) { "Module '${module.id}' is missing dependencies: ${missing.sorted().joinToString()}" }
            }

            val result = mutableListOf<AechronisModule>()
            val visiting = linkedSetOf<String>()
            val visited = mutableSetOf<String>()

            fun visit(module: AechronisModule) {
                if (module.id in visited) return
                check(visiting.add(module.id)) { "Module dependency cycle: ${(visiting + module.id).joinToString(" -> ")}" }
                module.dependencies.sorted().forEach { visit(byId.getValue(it)) }
                visiting.remove(module.id)
                visited += module.id
                result += module
            }

            byId.values.sortedBy { it.id }.forEach(::visit)
            return result
        }

        private fun stageGeneration(
            sourceJars: List<Path>,
            disabled: Set<String>,
        ): Generation {
            val stagingDirectory = Files.createTempDirectory("aechronis-modules-generation-")
            var classLoader: URLClassLoader? = null
            try {
                val stagedJars =
                    sourceJars.mapIndexed { index, source ->
                        val target = stagingDirectory.resolve("${index.toString().padStart(4, '0')}-${source.fileName}")
                        copyStable(source, target)
                        target
                    }
                classLoader =
                    URLClassLoader(
                        stagedJars.map { it.toUri().toURL() }.toTypedArray(),
                        AechronisModule::class.java.classLoader,
                    )
                val modules =
                    withContextClassLoader(classLoader) {
                        ServiceLoader.load(AechronisModule::class.java, classLoader).toList()
                    }

                val stagedByPath = stagedJars.associateBy { it.toAbsolutePath().normalize() }
                val providerCounts = stagedJars.associateWith { 0 }.toMutableMap()
                val available =
                    modules.map { module ->
                        validateIds(module)
                        val source =
                            runCatching {
                                Path
                                    .of(
                                        module.javaClass.protectionDomain.codeSource.location
                                            .toURI(),
                                    ).toAbsolutePath()
                                    .normalize()
                            }.getOrNull()
                        val stagedJar =
                            source?.let(stagedByPath::get)
                                ?: error("Module '${module.id}' was not loaded from a staged module JAR")
                        providerCounts[stagedJar] = providerCounts.getValue(stagedJar) + 1
                        AvailableModule(module, originalJarName(stagedJar.fileName.toString()))
                    }

                val duplicate = available.groupBy { it.module.id }.filterValues { it.size > 1 }.keys
                require(duplicate.isEmpty()) { "Duplicate module ids: ${duplicate.sorted().joinToString()}" }
                providerCounts.forEach { (jar, count) ->
                    require(count == 1) {
                        "Module JAR '${originalJarName(
                            jar.fileName.toString(),
                        )}' must declare exactly one AechronisModule provider (found $count)"
                    }
                }
                return Generation(stagingDirectory, stagedJars, classLoader, available.sortedBy { it.module.id }, disabled)
            } catch (error: Throwable) {
                runCatching { classLoader?.close() }
                deleteTree(stagingDirectory)
                throw error
            }
        }

        private fun moduleJars(directory: Path): List<Path> =
            Files.list(directory).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.extension.equals("jar", ignoreCase = true) }
                    .sorted()
                    .toList()
            }

        internal fun moduleJarsForGeneration(directory: Path): List<Path> =
            moduleJars(directory).also { jars ->
                require(jars.isNotEmpty()) {
                    "No runtime module JARs were found directly in $directory; deploy the modules directory beside aechronis.jar"
                }
            }

        private fun copyStable(
            source: Path,
            target: Path,
        ) {
            val before = Files.readAttributes(source, BasicFileAttributes::class.java)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            val after = Files.readAttributes(source, BasicFileAttributes::class.java)
            require(
                before.size() == after.size() &&
                    before.lastModifiedTime() == after.lastModifiedTime() &&
                    before.fileKey() == after.fileKey(),
            ) { "Module JAR changed while it was being staged: $source" }
        }

        private fun originalJarName(stagedName: String): String = stagedName.replace(Regex("^(?:[0-9]{4}-)+"), "")

        private fun validateIds(module: AechronisModule) {
            require(module.id.matches(MODULE_ID)) { "Invalid module id '${module.id}'" }
            module.dependencies.forEach { dependency ->
                require(dependency.matches(MODULE_ID)) {
                    "Module '${module.id}' has invalid dependency id '$dependency'"
                }
            }
            module.conflicts.forEach { conflict ->
                require(conflict.matches(MODULE_ID)) {
                    "Module '${module.id}' has invalid conflict id '$conflict'"
                }
            }
        }

        private fun dependencyClosure(
            module: AechronisModule,
            available: Map<String, AvailableModule>,
        ): Set<String> {
            val result = linkedSetOf<String>()

            fun visit(id: String) {
                if (!result.add(id)) return
                available[id]?.module?.dependencies?.forEach(::visit)
            }
            visit(module.id)
            return result
        }

        private fun directDependants(
            id: String,
            available: Map<String, AvailableModule>,
        ): Set<String> = available.values.filterTo(linkedSetOf()) { id in it.module.dependencies }.mapTo(linkedSetOf()) { it.module.id }

        private fun dependantClosure(
            id: String,
            available: Map<String, AvailableModule>,
        ): Set<String> {
            val result = linkedSetOf<String>()

            fun visit(dependency: String) {
                directDependants(dependency, available).forEach { dependant ->
                    if (result.add(dependant)) visit(dependant)
                }
            }
            visit(id)
            return result
        }

        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            runCatching {
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }.onFailure { report("Failed to delete staged module directory $root", it) }
        }

        private fun failureMessage(error: Throwable): String = error.message ?: error.javaClass.simpleName

        private fun report(
            message: String,
            error: Throwable,
        ) {
            System.err.println("[Modules] $message: ${failureMessage(error)}")
            error.printStackTrace()
        }
    }

    private fun readDisabled(): Set<String> {
        if (!Files.isRegularFile(disabledFile)) return emptySet()
        return Files
            .readAllLines(disabledFile)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .onEach { id -> require(id.matches(MODULE_ID)) { "Invalid module id '$id' in $disabledFile" } }
            .toSet()
    }

    private fun readModuleIds(
        file: Path,
        description: String,
    ): Set<String> {
        if (!Files.isRegularFile(file)) return emptySet()
        return Files
            .readAllLines(file)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .onEach { id -> require(id.matches(MODULE_ID)) { "Invalid $description id '$id' in $file" } }
            .toSet()
    }
}
