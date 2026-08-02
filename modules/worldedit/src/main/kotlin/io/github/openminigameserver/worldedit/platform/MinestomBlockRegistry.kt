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

    private fun Block.withProps(vararg properties: String): Int {
        for (alt in possibleStates()) {
            val altProps = alt.properties().map { "${it.key}=${it.value}" }
            if (properties.toList().containsAll(altProps)) {
                return alt.stateId()
            }
        }
        return this.stateId()
    }

    override fun getInternalBlockStateId(state: BlockState): OptionalInt {
        val result = blockMap[state.asString]?.stateId()?.let { OptionalInt.of(it) }
        if (result != null) {
            return result
        } else {
            val type = blockMap[state.blockType.id()]
            val states = state.states.map { "${it.key.name}=${it.value}" }.toTypedArray()
            val newState = type?.withProps(*states)
            return newState?.let { OptionalInt.of(it) } ?: super.getInternalBlockStateId(
                state,
            )
        }
    }
}
