package io.github.openminigameserver.worldedit.platform

import com.sk89q.worldedit.registry.state.BooleanProperty
import com.sk89q.worldedit.registry.state.EnumProperty
import com.sk89q.worldedit.registry.state.IntegerProperty
import com.sk89q.worldedit.registry.state.Property
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.registry.BundledBlockRegistry
import net.minestom.server.instance.block.Block
import java.util.OptionalInt

object MinestomBlockRegistry : BundledBlockRegistry() {
    private val blockMap = Block.values().associateBy { it.key().asString() }

    override fun getProperties(blockType: BlockType): MutableMap<String, out Property<*>> {
        val base = blockMap[blockType.id()]
        val grouped =
            base
                ?.possibleStates()
                ?.flatMap { alternative ->
                    alternative.properties().toList().distinct()
                }?.groupBy { it.first }

        val map =
            grouped
                ?.map {
                    it.key to createProperty(it)
                }?.let { mutableMapOf(*it.toTypedArray()) }
        return map ?: super.getProperties(blockType)!!
    }

    private val booleanValues = arrayOf("true", "false")

    private fun createProperty(it: Map.Entry<String, List<Pair<String, String>>>): Property<*> {
        val name = it.key
        val allValues =
            it.value
                .distinct()
                .map { it.second }
                .toMutableList()
        return when {
            allValues.all { booleanValues.contains(it) } -> {
                BooleanProperty(name, allValues.map { it.toBoolean() }.sortedBy { it })
            }

            allValues.all { it.toIntOrNull() != null } -> {
                IntegerProperty(name, allValues.map { it.toInt() }.sortedBy { it })
            }

            else -> {
                EnumProperty(name, allValues.sortedBy { it })
            }
        }
    }

    private fun Block.nativeStateIdForProperties(properties: Map<String, String>): Int? =
        possibleStates().firstOrNull { it.properties() == properties }?.stateId()

    /** Convert a Minestom state to the corresponding WorldEdit state. */
    fun getWorldEditBlockState(nativeState: Block): BlockState? {
        val blockType = BlockType.REGISTRY[nativeState.key().asString()] ?: return null
        val values =
            nativeState
                .properties()
                .mapNotNull { (name, value) ->
                    val property = blockType.getPropertyMap()[name] ?: return@mapNotNull null
                    property to property.getValueFor(value)
                }.toMap()
        if (values.size != nativeState.properties().size) return null
        return runCatching { blockType.getState(values) }.getOrNull()
    }

    override fun getInternalBlockStateId(state: BlockState): OptionalInt {
        val type = blockMap[state.blockType.id()]
        if (type != null) {
            val properties = state.states.entries.associate { it.key.name to it.value.toString() }
            type.nativeStateIdForProperties(properties)?.let { return OptionalInt.of(it) }
        }

        return super.getInternalBlockStateId(state)
    }
}
