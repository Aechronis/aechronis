package net.aechronis.vanilla.blocks.updates

interface BlockUpdateInfo {
    class DestroyBlock : BlockUpdateInfo

    class PlaceBlock : BlockUpdateInfo

    class ChunkLoad : BlockUpdateInfo

    class MoveBlock : BlockUpdateInfo

    companion object {
        fun destroyBlock(): DestroyBlock = DestroyBlock()

        fun placeBlock(): PlaceBlock = PlaceBlock()

        fun chunkLoad(): ChunkLoad = ChunkLoad()

        fun moveBlock(): MoveBlock = MoveBlock()
    }
}
