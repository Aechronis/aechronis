package net.aechronis.vanilla.blocks

import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.block.Block

object Blocks {
    private val entries = mutableListOf<Pair<Block, (Block) -> BlockBehaviour>>()

    fun register(
        block: Block,
        factory: (Block) -> BlockBehaviour,
    ) {
        entries.add(block to factory)
    }

    fun init() {
        val stateMap = HashMap<Int, BlockBehaviour>()

        for ((block, factory) in entries) {
            val behaviour = factory(block)
            for (state in block.possibleStates()) {
                stateMap[state.stateId()] = behaviour
            }
        }

        MinecraftServer.getGlobalEventHandler().addListener(PlayerBlockPlaceEvent::class.java) { event ->
            val behaviour = stateMap[event.block.stateId()] ?: return@addListener
            event.block = event.block.withHandler(behaviour)
        }
    }
}
