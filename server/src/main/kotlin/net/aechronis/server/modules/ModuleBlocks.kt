package net.aechronis.server.modules

import net.kyori.adventure.key.Key
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.instance.block.rule.BlockPlacementRule
import net.minestom.server.tag.Tag
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * Stable, server-owned bridges for Minestom block registrations. Minestom cannot unregister a
 * handler or placement rule, and loaded block palettes retain handler instances. These proxies
 * stay stable while their module-owned delegates are replaced or cleared between generations.
 */
object ModuleBlocks {
    private val handlers = ConcurrentHashMap<Key, HandlerBridge>()
    private val placementRules = ConcurrentHashMap<Int, PlacementRuleBridge>()

    @Volatile
    private var activeScope: ModuleResourceScope? = null

    internal fun activate(scope: ModuleResourceScope) {
        check(activeScope == null) { "Module block bridges already have an active generation" }
        activeScope = scope
    }

    internal fun deactivate(scope: ModuleResourceScope) {
        if (activeScope !== scope) return
        handlers.values.forEach { it.clear(scope) }
        placementRules.values.forEach { it.clear(scope) }
        activeScope = null
    }

    fun registerHandler(
        key: Key,
        supplier: Supplier<out BlockHandler>,
    ) {
        val manager = MinecraftServer.getBlockManager()
        val scope = activeScope
        if (scope == null) {
            check(!ModuleRuntime.isManagedRuntime()) { "No module generation is active" }
            manager.registerHandler(key, supplier)
            return
        }
        check(ModuleRuntime.captureScope() === scope) { "Block handler registration belongs to an inactive module generation" }
        var bridgeRegistered = false
        val bridge =
            handlers.computeIfAbsent(key) {
                HandlerBridge(key, manager.getHandler(key.asString())).also { created ->
                    manager.registerHandler(key) { created }
                    bridgeRegistered = true
                }
            }
        bridge.install(scope, supplier.get())
        if (!bridgeRegistered) {
            manager.registerHandler(key) { bridge }
        }
    }

    /**
     * Installs a reloadable handler only when no handler existed before the module system first
     * claimed this key. A bridge installed by an earlier generation is recognized and reused.
     */
    fun registerHandlerIfAbsent(
        key: Key,
        supplier: Supplier<out BlockHandler>,
    ): Boolean {
        val manager = MinecraftServer.getBlockManager()
        val scope = activeScope
        if (scope == null) {
            check(!ModuleRuntime.isManagedRuntime()) { "No module generation is active" }
            if (manager.getHandler(key.asString()) != null) return false
            manager.registerHandler(key, supplier)
            return true
        }
        check(ModuleRuntime.captureScope() === scope) { "Block handler registration belongs to an inactive module generation" }
        val existingBridge = handlers[key]
        val bridge =
            if (existingBridge != null) {
                if (!existingBridge.wasAbsentBeforeClaim() || manager.getHandler(key.asString()) !== existingBridge) return false
                existingBridge
            } else {
                if (manager.getHandler(key.asString()) != null) return false
                HandlerBridge(key, null).also { created ->
                    handlers[key] = created
                    manager.registerHandler(key) { created }
                }
            }
        bridge.install(scope, supplier.get())
        return true
    }

    fun registerPlacementRule(rule: BlockPlacementRule) {
        registerPlacementRule(rule.block) { rule }
    }

    /**
     * Installs a reloadable placement rule built around the rule that existed before the module
     * system first claimed this block. Supplying that stable fallback avoids a later generation
     * accidentally wrapping the bridge itself and recursively delegating back into itself.
     */
    fun registerPlacementRule(
        block: Block,
        factory: (BlockPlacementRule?) -> BlockPlacementRule,
    ) {
        val manager = MinecraftServer.getBlockManager()
        val scope = activeScope
        if (scope == null) {
            check(!ModuleRuntime.isManagedRuntime()) { "No module generation is active" }
            val rule = factory(manager.getBlockPlacementRule(block))
            require(rule.block == block) { "Placement-rule factory returned a rule for ${rule.block}, expected $block" }
            manager.registerBlockPlacementRule(rule)
            return
        }
        check(ModuleRuntime.captureScope() === scope) { "Block placement rule belongs to an inactive module generation" }
        var bridgeRegistered = false
        val bridge =
            placementRules.computeIfAbsent(block.id()) {
                PlacementRuleBridge(block, manager.getBlockPlacementRule(block)).also { created ->
                    manager.registerBlockPlacementRule(created)
                    bridgeRegistered = true
                }
            }
        val rule = bridge.createReplacement(factory)
        bridge.install(scope, rule)
        if (!bridgeRegistered) {
            manager.registerBlockPlacementRule(bridge)
        }
    }

    private class HandlerBridge(
        private val key: Key,
        private val fallback: BlockHandler?,
    ) : BlockHandler {
        @Volatile
        private var owner: ModuleResourceScope? = null

        @Volatile
        private var delegate: BlockHandler? = null

        fun install(
            scope: ModuleResourceScope,
            handler: BlockHandler,
        ) {
            owner = scope
            delegate = handler
        }

        fun clear(scope: ModuleResourceScope) {
            if (owner !== scope) return
            delegate = null
            owner = null
        }

        fun wasAbsentBeforeClaim(): Boolean = fallback == null

        private fun current(): BlockHandler? = delegate ?: fallback

        override fun onPlace(placement: BlockHandler.Placement) {
            current()?.onPlace(placement)
        }

        override fun onDestroy(destroy: BlockHandler.Destroy) {
            current()?.onDestroy(destroy)
        }

        override fun onInteract(interaction: BlockHandler.Interaction): Boolean = current()?.onInteract(interaction) ?: true

        override fun onTouch(touch: BlockHandler.Touch) {
            current()?.onTouch(touch)
        }

        override fun tick(tick: BlockHandler.Tick) {
            current()?.tick(tick)
        }

        override fun isTickable(): Boolean = current()?.isTickable ?: false

        override fun getBlockEntityTags(): Collection<Tag<*>> = current()?.blockEntityTags ?: emptyList()

        override fun getBlockEntityAction(): Byte = current()?.blockEntityAction ?: -1

        override fun getKey(): Key = current()?.key ?: key
    }

    internal class PlacementRuleBridge(
        block: Block,
        private val fallback: BlockPlacementRule?,
    ) : BlockPlacementRule(block) {
        @Volatile
        private var owner: ModuleResourceScope? = null

        @Volatile
        private var delegate: BlockPlacementRule? = null

        fun install(
            scope: ModuleResourceScope,
            rule: BlockPlacementRule,
        ) {
            owner = scope
            delegate = rule
        }

        fun clear(scope: ModuleResourceScope) {
            if (owner !== scope) return
            delegate = null
            owner = null
        }

        fun createReplacement(factory: (BlockPlacementRule?) -> BlockPlacementRule): BlockPlacementRule =
            factory(fallback).also { rule ->
                require(rule.block == block) { "Placement-rule factory returned a rule for ${rule.block}, expected $block" }
            }

        private fun current(): BlockPlacementRule? = delegate ?: fallback

        override fun blockUpdate(updateState: UpdateState): Block = current()?.blockUpdate(updateState) ?: updateState.currentBlock

        override fun blockPlace(placementState: PlacementState): Block? = current()?.blockPlace(placementState) ?: placementState.block

        override fun isSelfReplaceable(replacement: Replacement): Boolean = current()?.isSelfReplaceable(replacement) ?: false

        override fun maxUpdateDistance(): Int = current()?.maxUpdateDistance() ?: DEFAULT_UPDATE_RANGE
    }
}
