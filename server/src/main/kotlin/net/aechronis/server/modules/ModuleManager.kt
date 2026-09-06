package net.aechronis.server.modules

import net.minestom.server.MinecraftServer
import net.minestom.server.network.packet.server.CachedPacket
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Serial lifecycle worker; game threads never wait for staging, persistence or teardown. */
class ModuleManager private constructor(
    private val directory: Path,
) : AutoCloseable,
    ModuleAdministration {
    private val worker =
        Executors.newSingleThreadExecutor(
            Thread
                .ofPlatform()
                .daemon()
                .name("module-lifecycle")
                .factory(),
        )
    private val submissionLock = Any()
    private val requiredModules = readIds(directory.resolve(".required-modules"))
    private var artifacts = linkedMapOf<String, ModuleArtifact>()
    private var loaded = linkedMapOf<String, LoadedModule>()
    private var disabled = readIds(directory.resolve(".disabled-modules"))
    private var context: ModuleContext? = null
    private var generation = 0L
    private val unsafeModules = mutableListOf<LoadedModule>()

    @Volatile private var closing = false

    @Volatile private var phase = "starting"

    @Volatile private var publicSnapshot = ModuleSnapshot(0, phase, emptyList())

    override fun snapshot(): ModuleSnapshot = publicSnapshot

    internal fun isAcceptingPlayers(): Boolean = !closing && (phase == "running" || phase == "staging")

    fun initialize(context: ModuleContext) {
        check(this.context == null) { "Modules are already initialized" }
        this.context = context
        try {
            val plan = plan(artifacts, disabled)
            loaded = instantiate(artifacts, plan, emptyMap())
            start(loaded.values.toList(), context)
            loaded.values.forEach(LoadedModule::publish)
            generation = 1
            phase = "running"
            updateSnapshot()
            refreshClients()
        } catch (error: Throwable) {
            stopAndClose(loaded.values.toList(), context).forEach(error::addSuppressed)
            phase = "degraded"
            updateSnapshot()
            throw error
        }
    }

    override fun enable(id: String): CompletableFuture<ModuleOperationResult> = submit("Enable '$id'", Action.ENABLE, id)

    override fun disable(
        id: String,
        cascade: Boolean,
    ): CompletableFuture<ModuleOperationResult> = submit("Disable '$id'", Action.DISABLE, id, cascade)

    override fun restart(id: String): CompletableFuture<ModuleOperationResult> = submit("Restart '$id'", Action.RESTART, id)

    override fun reload(): CompletableFuture<ModuleOperationResult> = submit("Reload modules", Action.RELOAD)

    private fun submit(
        description: String,
        action: Action,
        id: String? = null,
        cascade: Boolean = false,
    ): CompletableFuture<ModuleOperationResult> =
        synchronized(submissionLock) {
            if (closing || phase != "running") {
                return@synchronized CompletableFuture.completedFuture(
                    ModuleOperationResult(false, "Module manager is ${if (closing) "closing" else phase}"),
                )
            }
            phase = "staging"
            publicSnapshot = publicSnapshot.copy(phase = phase)
            val operation = CompletableFuture.supplyAsync({ transition(description, action, id, cascade) }, worker)
            // A caller cancelling its view must not interrupt persistence or release operation ownership.
            operation.minimalCompletionStage().toCompletableFuture()
        }

    private fun transition(
        description: String,
        action: Action,
        id: String?,
        cascade: Boolean,
    ): ModuleOperationResult {
        val context = checkNotNull(context)
        val staged = mutableListOf<ModuleArtifact>()
        var candidates = linkedMapOf<String, LoadedModule>()
        var changed = emptySet<String>()
        var prepared = false
        var stopped = false
        var gameplayPause: AutoCloseable? = null
        val startedAt = System.nanoTime()
        try {
            staged += measured("stage JARs") { ModuleArtifact.stage(moduleJarsForGeneration(directory)) }
            val proposed = staged.associateByTo(linkedMapOf()) { it.definition.id }
            var nextDisabled = disabled
            when (action) {
                Action.ENABLE -> {
                    require(id in proposed) { "Unknown module '$id'" }
                    require(id !in loaded) { "Module '$id' is already enabled" }
                    nextDisabled = disabled - dependencyClosure(checkNotNull(id), proposed)
                }
                Action.DISABLE -> {
                    require(id in artifacts) { "Unknown module '$id'" }
                    require(id in loaded) { "Module '$id' is already disabled" }
                    val dependants = dependantClosure(setOf(checkNotNull(id)), artifacts) intersect loaded.keys
                    require(
                        cascade || dependants.isEmpty(),
                    ) { "Module '$id' is required by ${dependants.sorted().joinToString()}; retry with cascade" }
                    nextDisabled = disabled + id + if (cascade) dependants else emptySet()
                }
                Action.RESTART -> {
                    require(id in artifacts) { "Unknown module '$id'" }
                    require(id in loaded) { "Module '$id' is disabled; enable it instead" }
                }
                Action.RELOAD -> Unit
            }
            val nextPlan = plan(proposed, nextDisabled)
            if (action == Action.RESTART ||
                action == Action.ENABLE
            ) {
                require(id in nextPlan) { "Module '$id' is unavailable in the staged graph" }
            }
            val seeds =
                (artifacts.keys + proposed.keys).filterTo(linkedSetOf()) { key ->
                    artifacts[key]?.fingerprint != proposed[key]?.fingerprint || (key in loaded) != (key in nextPlan)
                }
            if (action == Action.RESTART) seeds += checkNotNull(id)
            changed = reloadClosure(seeds, artifacts, proposed, loaded.keys + nextPlan)
            val oldAffected = loaded.filterKeys { it in changed }.values.toList()
            val survivors = loaded.filterKeys { it !in changed }
            // Unchanged modules retain the exact original loader and object graph.
            candidates = instantiate(proposed, nextPlan, survivors)
            val replacements = candidates.filterKeys { it in changed }.values.toList()
            measured("prepare resource packs") { replacements.forEach { it.prepareResources(context) } }
            if (closing) error("Server shutdown began while staging modules")
            if (oldAffected.isEmpty() && replacements.isEmpty()) {
                writeDisabled(nextDisabled)
                commitArtifacts(proposed, nextDisabled)
                phase = "running"
                updateSnapshot()
                return ModuleOperationResult(true, "$description completed; no running modules changed")
            }

            phase = "reloading"
            publicSnapshot = publicSnapshot.copy(phase = phase)
            gameplayPause = measured("pause gameplay") { context.pauseGameplay() }
            // Inventory close listeners must settle before their module callbacks are detached.
            context.runLive {
                MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
                    if (player.openInventory != null) player.closeInventory()
                }
            }
            prepared = true
            measured("quiesce affected modules") {
                oldAffected.asReversed().forEach(LoadedModule::detachInputs)
                oldAffected.asReversed().forEach(LoadedModule::quiesce)
                oldAffected.asReversed().forEach { it.prepareForShutdown(context) }
            }
            measured("persist affected modules and world") {
                oldAffected.asReversed().forEach { it.saveState(context) }
                // This wait is owned by the lifecycle worker, never by a server tick or command.
                context.saveCoreWorld()
            }
            measured("stop affected modules") {
                context.removeResourcePacksFromOnlinePlayers(oldAffected.mapTo(hashSetOf()) { it.module.id })
                oldAffected.asReversed().forEach { it.stop(context) }
            }
            stopped = true
            measured("start replacement modules") { start(replacements, context, resourcesPrepared = true) }
            writeDisabled(nextDisabled)
            context.runLive {
                replacements.forEach(LoadedModule::publish)
                refreshClients()
                runCatching(context::sendResourcePacksToOnlinePlayers).onFailure { report("Refresh resource packs", it) }
            }
            loaded = candidates
            commitArtifacts(proposed, nextDisabled, oldAffected)
            generation += 1
            phase = "running"
            updateSnapshot()
            return ModuleOperationResult(
                true,
                "$description completed",
                oldAffected.mapTo(linkedSetOf()) { it.module.id } + replacements.map { it.module.id },
            )
        } catch (error: Throwable) {
            report(description, error)
            val replacementModules = candidates.filter { (id, module) -> loaded[id] !== module }.values.toList()
            val cleanupFailures = stopAndClose(replacementModules, context)
            if (cleanupFailures.isNotEmpty()) {
                cleanupFailures.forEach(error::addSuppressed)
                failClosed(context)
            } else if (stopped) {
                try {
                    val rollback = instantiate(artifacts, plan(artifacts, disabled), loaded.filterKeys { it !in changed })
                    val replacements = rollback.filterKeys { it in changed }.values.toList()
                    // Keep partially started rollback instances reachable if any lifecycle hook fails.
                    unsafeModules += replacements
                    start(replacements, context)
                    loaded.filterKeys { it in changed }.values.forEach(LoadedModule::close)
                    loaded = rollback
                    unsafeModules.removeAll(replacements.toSet())
                    writeDisabled(disabled)
                    generation += 1
                    context.runLive {
                        replacements.forEach(LoadedModule::publish)
                        refreshClients()
                        context.sendResourcePacksToOnlinePlayers()
                    }
                    phase = "running"
                    updateSnapshot()
                    return ModuleOperationResult(
                        false,
                        "$description failed; previous modules restored: ${error.message}",
                        changed,
                        rolledBack = true,
                    )
                } catch (rollbackError: Throwable) {
                    error.addSuppressed(rollbackError)
                    failClosed(context)
                }
            } else if (prepared) {
                // Prepared modules cannot safely resume; preserve their state for coordinated shutdown.
                failClosed(context)
            } else {
                phase = "running"
                updateSnapshot()
            }
            return ModuleOperationResult(
                false,
                "$description failed: ${error.message}" + if (phase == "degraded") "; server is shutting down" else "",
                changed,
            )
        } finally {
            if (gameplayPause != null) {
                try {
                    if (phase == "degraded") {
                        // Never resume player input with partially torn-down protection modules.
                        context.runLive {
                            MinecraftServer.getConnectionManager().onlinePlayers.toList().forEach {
                                it.kick(
                                    net.kyori.adventure.text.Component
                                        .text("Module reload failed; server is shutting down"),
                                )
                            }
                        }
                    }
                } finally {
                    gameplayPause.close()
                }
            }
            val retained = artifacts.values.toSet() + unsafeModules.map { it.artifact }
            staged.filterNot { it in retained }.forEach { runCatching(it::close).onFailure { error -> report("Close staged JAR", error) } }
            println("[Modules] $description took ${(System.nanoTime() - startedAt) / 1_000_000}ms")
        }
    }

    private fun commitArtifacts(
        proposed: LinkedHashMap<String, ModuleArtifact>,
        nextDisabled: Set<String>,
        retired: List<LoadedModule> = emptyList(),
    ) {
        retired.forEach { runCatching(it::close).onFailure { error -> report("Close retired loader", error) } }
        val next = proposed.mapValuesTo(linkedMapOf()) { (id, artifact) -> loaded[id]?.artifact ?: artifact }
        artifacts.values.filterNot { it in next.values }.forEach {
            runCatching(it::close).onFailure { error ->
                report("Delete retired JAR", error)
            }
        }
        artifacts = next
        disabled = nextDisabled
    }

    private fun start(
        modules: List<LoadedModule>,
        context: ModuleContext,
        resourcesPrepared: Boolean = false,
    ) {
        if (!resourcesPrepared) modules.forEach { it.prepareResources(context) }
        // Initializers can synchronously announce services and receive replies (e.g. vote rewards).
        // Background game callbacks remain gated until the whole affected graph is ready.
        ModuleRuntime.withLifecycleCallbacks {
            modules.asReversed().forEach { it.configure(context) }
            modules.forEach { it.start(context) }
        }
    }

    private fun instantiate(
        available: Map<String, ModuleArtifact>,
        ids: List<String>,
        survivors: Map<String, LoadedModule>,
    ): LinkedHashMap<String, LoadedModule> {
        val result = linkedMapOf<String, LoadedModule>()
        try {
            ids.forEach { id ->
                result[id] =
                    survivors[id]
                        ?: LoadedModule(
                            available.getValue(id),
                            available
                                .getValue(id)
                                .definition.dependencies
                                .sorted()
                                .map { result.getValue(it) },
                        )
            }
            return result
        } catch (error: Throwable) {
            result.filter { (id, module) -> survivors[id] !== module }.values.forEach { runCatching(it::close) }
            throw error
        }
    }

    private fun stopAndClose(
        modules: List<LoadedModule>,
        context: ModuleContext,
    ): List<Throwable> =
        buildList {
            // Drain the entire affected graph before any teardown can mutate a dependency.
            val quiescence =
                runCatching {
                    modules.asReversed().forEach(LoadedModule::detachInputs)
                    modules.asReversed().forEach(LoadedModule::quiesce)
                }.exceptionOrNull()
            if (quiescence != null) {
                unsafeModules += modules
                add(quiescence)
                return@buildList
            }
            modules.asReversed().forEach { module ->
                runCatching {
                    module.stop(context)
                    module.close()
                }.onFailure {
                    unsafeModules += module
                    add(it)
                }
            }
        }

    private fun failClosed(context: ModuleContext) {
        phase = "degraded"
        updateSnapshot()
        context.shutdownServer()
    }

    fun beginShutdown() {
        synchronized(submissionLock) { closing = true }
        // Shutdown itself runs on the server shutdown worker, so the tick can finish a handover.
        CompletableFuture.runAsync({}, worker).join()
    }

    fun prepareForShutdown(context: ModuleContext) =
        onWorker {
            phase = "closing"
            updateSnapshot()
            val modules = (loaded.values + unsafeModules).distinct()
            modules.asReversed().forEach(LoadedModule::detachInputs)
            modules.asReversed().forEach(LoadedModule::quiesce)
            modules.asReversed().forEach { it.prepareForShutdown(context) }
        }

    fun saveState(context: ModuleContext) =
        onWorker {
            loaded.values
                .toList()
                .asReversed()
                .forEach { it.saveState(context) }
        }

    fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> =
        synchronized(submissionLock) {
            if (closing || phase != "running") return@synchronized CompletableFuture.completedFuture(null)
            CompletableFuture
                .supplyAsync({
                    if (closing || phase != "running") emptyList() else loaded.values.map { it.saveCheckpoint(context) }
                }, worker)
                .thenCompose { CompletableFuture.allOf(*it.toTypedArray()) }
        }

    override fun close() {
        if (worker.isShutdown) return
        beginShutdown()
        try {
            onWorker {
                val context = context
                val failures = if (context == null) emptyList() else stopAndClose((loaded.values + unsafeModules).distinct(), context)
                check(failures.isEmpty()) { "Module teardown failed: ${failures.joinToString { it.message.orEmpty() }}" }
                loaded.clear()
                artifacts.values.forEach(ModuleArtifact::close)
                artifacts.clear()
                phase = "closed"
                updateSnapshot()
            }
        } finally {
            worker.shutdown()
        }
    }

    private fun onWorker(action: () -> Unit) = CompletableFuture.runAsync(action, worker).join()

    private fun plan(
        available: Map<String, ModuleArtifact>,
        disabled: Set<String>,
    ): List<String> {
        require((requiredModules - available.keys).isEmpty()) { "Required module JARs are missing: ${requiredModules - available.keys}" }
        val enabled = available.keys.filterTo(linkedSetOf()) { it !in disabled }
        while (enabled.removeIf { id ->
                available
                    .getValue(id)
                    .definition.dependencies
                    .any { it !in enabled }
            }
        ) { /* dependency closure */ }
        require(requiredModules.all { it in disabled || it in enabled }) { "Required modules have unavailable dependencies" }
        enabled.forEach { id ->
            require(
                available
                    .getValue(id)
                    .definition.conflicts
                    .none { it in enabled },
            ) { "Module '$id' conflicts with an enabled module" }
        }
        return sort(enabled.map { available.getValue(it).definition }).map { it.id }
    }

    private fun updateSnapshot() {
        publicSnapshot =
            ModuleSnapshot(
                generation,
                phase,
                artifacts.map { (id, artifact) ->
                    ModuleStatus(
                        id,
                        loaded[id]?.running == true,
                        artifact.definition.dependencies,
                        artifacts
                            .filterValues {
                                id in
                                    it.definition.dependencies
                            }.keys,
                        artifact.sourceName,
                        if (id in
                            disabled
                        ) {
                            "disabled by administrator"
                        } else if (id !in loaded) {
                            "dependency unavailable or module not started"
                        } else {
                            null
                        },
                    )
                },
            )
    }

    private fun writeDisabled(ids: Set<String>) {
        val target = directory.resolve(".disabled-modules")
        val temporary = directory.resolve(".disabled-${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, ids.sorted().joinToString("\n", postfix = if (ids.isEmpty()) "" else "\n"))
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private enum class Action { ENABLE, DISABLE, RESTART, RELOAD }

    companion object {
        fun discover(directory: Path): ModuleManager {
            Files.createDirectories(directory)
            val manager = ModuleManager(directory)
            try {
                ModuleArtifact.stage(moduleJarsForGeneration(directory)).forEach { manager.artifacts[it.definition.id] = it }
                manager.plan(manager.artifacts, manager.disabled)
                manager.updateSnapshot()
                return manager
            } catch (error: Throwable) {
                manager.artifacts.values.forEach(ModuleArtifact::close)
                manager.worker.shutdown()
                throw error
            }
        }

        internal fun sort(modules: List<AechronisModule>): List<AechronisModule> {
            val byId = modules.associateBy { it.id }
            require(byId.size == modules.size) { "Duplicate module ids" }
            val visited = hashSetOf<String>()
            val visiting = linkedSetOf<String>()
            val result = mutableListOf<AechronisModule>()

            fun visit(id: String) {
                if (id in visited) return
                check(visiting.add(id)) { "Module dependency cycle: ${(visiting + id).joinToString(" -> ")}" }
                val module = byId[id] ?: error("Missing dependency '$id'")
                module.dependencies.sorted().forEach(::visit)
                visiting.remove(id)
                visited += id
                result += module
            }
            byId.keys.sorted().forEach(::visit)
            return result
        }

        internal fun moduleJarsForGeneration(directory: Path): List<Path> =
            Files
                .list(directory)
                .use { paths ->
                    paths.filter { it.isRegularFile() && it.extension.equals("jar", true) }.sorted().toList()
                }.also {
                    require(
                        it.isNotEmpty(),
                    ) { "No runtime module JARs were found directly in $directory; deploy the modules directory beside aechronis.jar" }
                }

        private fun dependencyClosure(
            id: String,
            available: Map<String, ModuleArtifact>,
        ): Set<String> {
            val result = linkedSetOf<String>()

            fun visit(key: String) {
                if (result.add(key)) available[key]?.definition?.dependencies?.forEach(::visit)
            }
            visit(id)
            return result
        }

        private fun dependantClosure(
            ids: Set<String>,
            available: Map<String, ModuleArtifact>,
        ): Set<String> {
            val result = ids.toMutableSet()
            do {
                val before = result.size
                available.forEach { (id, artifact) -> if (artifact.definition.dependencies.any { it in result }) result += id }
            } while (result.size != before)
            return result - ids
        }

        internal fun reloadClosure(
            seeds: Set<String>,
            old: Map<String, ModuleArtifact>,
            staged: Map<String, ModuleArtifact>,
            active: Set<String>,
        ): Set<String> {
            val result = seeds.toMutableSet()
            do {
                val before = result.size
                listOf(old, staged).forEach { graph ->
                    graph.forEach { (id, artifact) ->
                        if (id in active && artifact.definition.dependencies.any { it in result }) result += id
                        if (id in active && id in result) result += artifact.definition.reloadTogether.filter { it in active }
                    }
                }
            } while (result.size != before)
            return result
        }

        private fun readIds(file: Path): Set<String> =
            if (!Files.isRegularFile(file)) {
                emptySet()
            } else {
                Files
                    .readAllLines(file)
                    .map(String::trim)
                    .filter {
                        it.isNotEmpty() && !it.startsWith('#')
                    }.onEach { require(it.matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "Invalid module ID '$it' in $file" } }
                    .toSet()
            }

        private fun refreshClients() {
            (MinecraftServer.getRecipeManager().declareRecipesPacket as? CachedPacket)?.invalidate()
            MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
                runCatching(player::refreshCommands)
                runCatching(player::refreshRecipes)
            }
        }

        private fun <T> measured(
            stage: String,
            action: () -> T,
        ): T {
            val started = System.nanoTime()
            return try {
                action()
            } finally {
                println("[Modules] $stage: ${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }

        private fun report(
            description: String,
            error: Throwable,
        ) {
            System.err.println("[Modules] $description failed: ${error.message}")
            error.printStackTrace()
        }
    }
}
