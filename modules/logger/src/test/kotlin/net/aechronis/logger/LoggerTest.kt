package net.aechronis.logger

import io.github.openminigameserver.worldedit.event.WorldEditBlockChange
import io.github.openminigameserver.worldedit.event.WorldEditBlockChangesEvent
import net.aechronis.combat.objects.Explosion
import net.aechronis.logger.listeners.LootListener
import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockLogEntry
import net.aechronis.logger.objects.FeatureLogEntry
import net.aechronis.logger.objects.RollbackActor
import net.aechronis.logger.objects.RollbackDomain
import net.aechronis.logger.objects.RollbackOperationKind
import net.aechronis.logger.objects.RollbackSelection
import net.aechronis.logger.objects.RollbackStatus
import net.aechronis.logger.objects.StorageChange
import net.aechronis.logger.objects.StorageChangeAction
import net.aechronis.logger.objects.StorageRollbackAdapters
import net.aechronis.logger.objects.VanillaStorage
import net.aechronis.logger.objects.snapshotItems
import net.aechronis.logger.params.FeatureLookupParams
import net.aechronis.logger.params.LookupParams
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.LogMetadata
import net.aechronis.utils.createTestServer
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventNode
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.item.PickupItemEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.inventory.PlayerInventory
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LoggerTest {
    private val databasePath = Path.of("build/logger_test.db").toAbsolutePath().normalize()

    @BeforeAll
    fun testInit() {
        Files.deleteIfExists(Path.of("$databasePath.mv.db"))
        createTestServer()

        Logger.init(LoggerConfig(databasePath = databasePath.toString()))
    }

    @Test
    @Order(1)
    fun `rollback restore and undo use durable history`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(0, 0).get(5, TimeUnit.SECONDS)
        val position = Pos(1.0, 40.0, 1.0)
        val playerUuid = UUID.randomUUID()
        val now = System.currentTimeMillis()

        Logger.repository
            .insertAsync(
                blockEntry(now - 2, playerUuid, instance.uuid, position, Block.STONE, Block.DIRT),
            ).get(5, TimeUnit.SECONDS)
        Logger.repository
            .insertAsync(
                blockEntry(now - 1, playerUuid, instance.uuid, position, Block.DIRT, Block.GOLD_BLOCK),
            ).get(5, TimeUnit.SECONDS)
        instance.setBlock(position, Block.GOLD_BLOCK)

        val params = LookupParams(users = listOf("rollback-test"), since = now - 10_000, radius = 5)
        val targetTs = requireNotNull(params.since)
        val actor = RollbackActor(UUID.randomUUID(), "operator")
        val rollbackPlan =
            Logger.rollbackService
                .computePlanAsync(RollbackOperationKind.ROLLBACK, params, targetTs, instance.uuid, position, safeMode = true)
                .get(5, TimeUnit.SECONDS)
        val rollbackResult = Logger.rollbackService.applyAsync(actor, rollbackPlan).get(5, TimeUnit.SECONDS)

        assertEquals(2, rollbackResult.appliedCount)
        assertEquals(Block.STONE, instance.getBlock(position))
        assertEquals(
            RollbackStatus.APPLIED,
            Logger.rollback
                .findOperationAsync(rollbackResult.operationId)
                .get(5, TimeUnit.SECONDS)
                ?.status,
        )

        val restorePlan =
            Logger.rollbackService
                .computePlanAsync(RollbackOperationKind.RESTORE, params, targetTs, instance.uuid, position, safeMode = true)
                .get(5, TimeUnit.SECONDS)
        val restoreResult = Logger.rollbackService.applyAsync(actor, restorePlan).get(5, TimeUnit.SECONDS)

        assertEquals(2, restoreResult.appliedCount)
        assertEquals(Block.GOLD_BLOCK, instance.getBlock(position))

        Logger.rollbackService.undoAsync(actor).get(5, TimeUnit.SECONDS)
        assertEquals(Block.STONE, instance.getBlock(position))

        Logger.rollbackService.redoAsync(actor).get(5, TimeUnit.SECONDS)
        assertEquals(Block.GOLD_BLOCK, instance.getBlock(position))
        Logger.rollbackService.undoAsync(actor).get(5, TimeUnit.SECONDS)
        assertEquals(Block.STONE, instance.getBlock(position))
    }

    @Test
    @Order(2)
    fun `entity spawn can be rolled back and undone`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(0, 0).get(5, TimeUnit.SECONDS)
        val entity =
            net.minestom.server.entity
                .Entity(net.minestom.server.entity.EntityType.ZOMBIE)
        entity.setInstance(instance, Pos(2.0, 40.0, 2.0)).get(5, TimeUnit.SECONDS)
        val target = System.currentTimeMillis() - 10_000
        val actor = RollbackActor(UUID.randomUUID(), "entity-operator")
        val plan =
            Logger.rollbackService
                .computePlanAsync(
                    RollbackOperationKind.ROLLBACK,
                    LookupParams(since = target, radius = 10),
                    target,
                    instance.uuid,
                    Pos(2.0, 40.0, 2.0),
                    safeMode = true,
                    selection = RollbackSelection(setOf(RollbackDomain.ENTITY)),
                ).get(5, TimeUnit.SECONDS)

        assertEquals(1, plan.totalChangeCount)
        Logger.rollbackService.applyAsync(actor, plan).get(5, TimeUnit.SECONDS)
        assertEquals(null, instance.getEntityByUuid(entity.uuid))
        Logger.rollbackService.undoAsync(actor).get(5, TimeUnit.SECONDS)
        assertEquals(net.minestom.server.entity.EntityType.ZOMBIE, instance.getEntityByUuid(entity.uuid)?.entityType)
    }

    @Test
    @Order(3)
    fun `storage adapter participates in rollback and undo`() {
        var storedAmount = 3
        StorageRollbackAdapters.register("test-storage") { _, _, _, amount, action ->
            if (action == StorageChangeAction.DEPOSIT) storedAmount += amount else storedAmount -= amount
            CompletableFuture.completedFuture(storedAmount >= 0)
        }
        Logger.storageChange
            .depositAsync(
                storageId = "chest-1",
                item =
                    net.minestom.server.item.ItemStack
                        .of(net.minestom.server.item.Material.DIAMOND),
                amount = 3,
                playerName = "storage-test",
                source = "test-storage",
            ).get(5, TimeUnit.SECONDS)

        val instance = MinecraftServer.getInstanceManager().instances.first()
        val target = System.currentTimeMillis() - 10_000
        val actor = RollbackActor(UUID.randomUUID(), "storage-operator")
        val plan =
            Logger.rollbackService
                .computePlanAsync(
                    RollbackOperationKind.ROLLBACK,
                    LookupParams(users = listOf("storage-test"), since = target, global = true),
                    target,
                    instance.uuid,
                    Pos.ZERO,
                    safeMode = true,
                    selection = RollbackSelection(setOf(RollbackDomain.STORAGE)),
                ).get(5, TimeUnit.SECONDS)

        Logger.rollbackService.applyAsync(actor, plan).get(5, TimeUnit.SECONDS)
        assertEquals(0, storedAmount)
        Logger.rollbackService.undoAsync(actor).get(5, TimeUnit.SECONDS)
        assertEquals(3, storedAmount)
        StorageRollbackAdapters.unregister("test-storage")
    }

    @Test
    @Order(4)
    fun `snapshot persistence excludes crafting slots`() {
        val inventory = PlayerInventory()
        inventory.setItemStack(0, ItemStack.of(Material.DIAMOND, 2))
        inventory.setItemStack(36, ItemStack.of(Material.CRAFTING_TABLE))
        inventory.setItemStack(41, ItemStack.of(Material.DIAMOND_HELMET))
        inventory.setItemStack(45, ItemStack.of(Material.SHIELD))
        val uuid = UUID.randomUUID()

        Logger.inventorySnapshot
            .deathAsync(uuid, "snapshot-test", snapshotItems(inventory))
            .get(5, TimeUnit.SECONDS)
        val snapshot =
            Logger.inventorySnapshot
                .findByPlayerNameAsync("SNAPSHOT-TEST", 10)
                .get(5, TimeUnit.SECONDS)
                .single()

        assertEquals(46, snapshot.items.size)
        assertEquals(ItemStack.of(Material.DIAMOND, 2), snapshot.items[0])
        assertEquals(ItemStack.AIR, snapshot.items[36])
        assertEquals(ItemStack.of(Material.DIAMOND_HELMET), snapshot.items[41])
        assertEquals(ItemStack.of(Material.SHIELD), snapshot.items[45])
    }

    @Test
    @Order(5)
    fun `vanilla barrel slot participates in rollback and undo`() {
        val instance = MinecraftServer.getInstanceManager().instances.first()
        val position = Pos(8.0, 40.0, 8.0)
        val key = Storage.keyFor(instance, position)
        val contents = StorageContents()
        instance.setBlock(position, Storage.withContents(Block.BARREL, contents))
        Storage.register(key, contents)
        val item = ItemStack.of(Material.EMERALD, 7)
        val target = System.currentTimeMillis() - 1_000

        contents.inventory.setItemStack(53, item)
        val rows =
            Logger.storageChange
                .searchForOperationAsync(
                    LookupParams(since = target, source = LogMetadata.VANILLA, global = true),
                    target,
                    setOf(StorageChangeAction.DEPOSIT),
                    rolledBack = false,
                    limit = 10,
                ).get(5, TimeUnit.SECONDS)
        assertEquals(53, rows.single().slot)

        val actor = RollbackActor(UUID.randomUUID(), "barrel-operator")
        val plan =
            Logger.rollbackService
                .computePlanAsync(
                    RollbackOperationKind.ROLLBACK,
                    LookupParams(since = target, source = LogMetadata.VANILLA, global = true),
                    target,
                    instance.uuid,
                    position,
                    safeMode = true,
                    selection = RollbackSelection(setOf(RollbackDomain.STORAGE)),
                ).get(5, TimeUnit.SECONDS)
        Logger.rollbackService.applyAsync(actor, plan).get(5, TimeUnit.SECONDS)
        assertEquals(ItemStack.AIR, contents.inventory.getItemStack(53))
        Logger.rollbackService.undoAsync(actor).get(5, TimeUnit.SECONDS)
        assertEquals(item, contents.inventory.getItemStack(53))

        Storage.remove(key)
        instance.setBlock(position, Block.AIR)
    }

    @Test
    @Order(6)
    fun `barrel breaks use normal block history`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(0, 0).get(5, TimeUnit.SECONDS)
        val position = Pos(12.0, 40.0, 12.0)
        val key = Storage.keyFor(instance, position)
        val contents = StorageContents()
        val storedItem = ItemStack.of(Material.DIAMOND, 9)
        contents.inventory.setItemStack(53, storedItem)
        val barrel = Storage.withContents(Block.BARREL, contents)
        instance.setBlock(position, barrel)
        Storage.register(key, contents)
        val player = createPlayer(instance, position.add(0.0, 1.0, 0.0), "barrel-test")

        MinecraftServer.getGlobalEventHandler().call(
            PlayerBlockBreakEvent(
                player,
                instance,
                barrel,
                Block.AIR,
                BlockVec(position.blockX(), position.blockY(), position.blockZ()),
                BlockFace.TOP,
            ),
        )
        Logger.repository.flushAsync().get(5, TimeUnit.SECONDS)
        val entry =
            Logger.repository
                .lookupAsync(position.blockX(), position.blockY(), position.blockZ())
                .get(5, TimeUnit.SECONDS)
                .single()

        assertEquals(BlockAction.BREAK, entry.action)
        assertEquals(Block.BARREL.key().asString(), entry.blockOld)
        assertEquals(barrel.nbt(), ItemCodec.decodeBlockNbt(entry.blockOldNbt))

        player.remove()
        Storage.remove(key)
        instance.setBlock(position, Block.AIR)
    }

    @Test
    @Order(7)
    fun `loot pickup and drop are searchable audits`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(0, 0).get(5, TimeUnit.SECONDS)
        val position = Pos(20.5, 40.0, 20.5)
        val player = createPlayer(instance, position, "loot-test")
        val pickupStack = ItemStack.of(Material.DIAMOND, 4).withCustomName(Component.text("Audit pickup"))
        val dropStack = ItemStack.of(Material.EMERALD, 3).withCustomName(Component.text("Audit drop"))
        val itemEntity = ItemEntity(pickupStack)
        itemEntity.setInstance(instance, position).get(5, TimeUnit.SECONDS)
        val since = System.currentTimeMillis() - 1_000

        LootListener.recordPickup(PickupItemEvent(player, itemEntity))!!.get(5, TimeUnit.SECONDS)
        LootListener.recordDrop(ItemDropEvent(player, dropStack))!!.get(5, TimeUnit.SECONDS)
        val cancelledPickup = PickupItemEvent(player, ItemEntity(ItemStack.of(Material.GOLD_INGOT))).apply { isCancelled = true }
        val cancelledDrop = ItemDropEvent(player, ItemStack.of(Material.GOLD_INGOT)).apply { isCancelled = true }
        assertNull(LootListener.recordPickup(cancelledPickup))
        assertNull(LootListener.recordDrop(cancelledDrop))

        val entries =
            Logger.featureLog
                .searchAsync(
                    FeatureLookupParams(
                        source = LogMetadata.LOOT,
                        users = listOf(player.username),
                        since = since,
                        actions = listOf("pickup", "drop"),
                    ),
                    position.blockX(),
                    position.blockY(),
                    position.blockZ(),
                ).get(5, TimeUnit.SECONDS)

        assertEquals(setOf("pickup", "drop"), entries.map { it.action }.toSet())
        val pickup = entries.single { it.action == "pickup" }
        val drop = entries.single { it.action == "drop" }
        assertEquals(pickupStack, ItemCodec.decodeItem(Base64.getDecoder().decode(pickup.data.getValue("item"))))
        assertEquals(dropStack, ItemCodec.decodeItem(Base64.getDecoder().decode(drop.data.getValue("item"))))
        assertEquals(itemEntity.uuid.toString(), pickup.data["entity_uuid"])
        assertEquals(instance.uuid.toString(), drop.data["instance_uuid"])

        itemEntity.remove()
        player.remove()
    }

    @Test
    @Order(8)
    fun `WorldEdit changes use normal block rollback and restore`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(1, 1).get(5, TimeUnit.SECONDS)
        val firstPosition = BlockVec(24, 40, 24)
        val secondPosition = BlockVec(25, 40, 24)
        val oldNbt = CompoundBinaryTag.builder().putString("logger-test", "old").build()
        val newNbt = CompoundBinaryTag.builder().putString("logger-test", "new").build()
        val oldFirst = Block.STONE.withNbt(oldNbt)
        val newFirst = Block.DIAMOND_BLOCK.withNbt(newNbt)
        val oldSecond = Block.GOLD_BLOCK
        val newSecond = Block.AIR
        val playerUuid = UUID.randomUUID()
        val since = System.currentTimeMillis() - 1_000

        instance.setBlock(firstPosition, newFirst)
        instance.setBlock(secondPosition, newSecond)
        MinecraftServer.getGlobalEventHandler().call(
            WorldEditBlockChangesEvent(
                actorUuid = playerUuid,
                actorName = "worldedit-test",
                instance = instance,
                changes =
                    listOf(
                        WorldEditBlockChange(firstPosition, oldFirst, newFirst),
                        WorldEditBlockChange(secondPosition, oldSecond, newSecond),
                    ),
            ),
        )
        Logger.repository.flushAsync().get(5, TimeUnit.SECONDS)

        val firstEntry =
            Logger.repository
                .lookupAsync(firstPosition.blockX(), firstPosition.blockY(), firstPosition.blockZ())
                .get(5, TimeUnit.SECONDS)
                .single()
        val secondEntry =
            Logger.repository
                .lookupAsync(secondPosition.blockX(), secondPosition.blockY(), secondPosition.blockZ())
                .get(5, TimeUnit.SECONDS)
                .single()
        assertEquals(BlockAction.PLACE, firstEntry.action)
        assertEquals(BlockAction.BREAK, secondEntry.action)
        assertEquals(playerUuid, firstEntry.playerUuid)
        assertEquals(instance.uuid, firstEntry.instanceUuid)
        assertEquals(LogMetadata.WORLDEDIT, firstEntry.source)
        assertEquals(LogMetadata.WORLDEDIT, firstEntry.origin)
        assertEquals(oldNbt, ItemCodec.decodeBlockNbt(firstEntry.blockOldNbt))
        assertEquals(newNbt, ItemCodec.decodeBlockNbt(firstEntry.blockNewNbt))

        val params =
            LookupParams(
                users = listOf("worldedit-test"),
                source = LogMetadata.WORLDEDIT,
                since = since,
                radius = 10,
            )
        val actor = RollbackActor(UUID.randomUUID(), "worldedit-operator")
        val center = Pos(firstPosition.x(), firstPosition.y(), firstPosition.z())
        val rollbackPlan =
            Logger.rollbackService
                .computePlanAsync(RollbackOperationKind.ROLLBACK, params, since, instance.uuid, center, safeMode = true)
                .get(5, TimeUnit.SECONDS)
        val rollbackResult = Logger.rollbackService.applyAsync(actor, rollbackPlan).get(5, TimeUnit.SECONDS)

        assertEquals(2, rollbackResult.appliedCount)
        assertEquals(oldFirst, instance.getBlock(firstPosition))
        assertEquals(oldSecond, instance.getBlock(secondPosition))

        val restorePlan =
            Logger.rollbackService
                .computePlanAsync(RollbackOperationKind.RESTORE, params, since, instance.uuid, center, safeMode = true)
                .get(5, TimeUnit.SECONDS)
        val restoreResult = Logger.rollbackService.applyAsync(actor, restorePlan).get(5, TimeUnit.SECONDS)

        assertEquals(2, restoreResult.appliedCount)
        assertEquals(newFirst, instance.getBlock(firstPosition))
        assertEquals(newSecond, instance.getBlock(secondPosition))
    }

    @Test
    @Order(9)
    fun `placed and filled barrel survives rollback restore and replay`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(2, 2).get(5, TimeUnit.SECONDS)
        val position = Pos(40.0, 40.0, 40.0)
        val key = Storage.keyFor(instance, position)
        val storageId = VanillaStorage.storageId(key)
        val slot = 17
        val item = ItemStack.of(Material.DIAMOND, 5)
        val playerUuid = UUID.randomUUID()
        val playerName = "combined-barrel-test"
        val targetTs = System.currentTimeMillis() - 10_000
        val placementTs = System.currentTimeMillis() - 2
        val depositTs = placementTs + 1
        val emptyBarrel = Storage.withContents(Block.BARREL, StorageContents())
        val liveContents = StorageContents().also { it.inventory.setItemStack(slot, item) }
        val liveBarrel = Storage.withContents(Block.BARREL, liveContents)

        Logger.repository
            .insertAsync(
                blockEntry(placementTs, playerUuid, instance.uuid, position, Block.AIR, emptyBarrel).copy(
                    playerName = playerName,
                    blockOldNbt = ItemCodec.encodeBlockNbt(Block.AIR.nbt()),
                    blockNewNbt = ItemCodec.encodeBlockNbt(emptyBarrel.nbt()),
                ),
            ).get(5, TimeUnit.SECONDS)
        Logger.storageChange
            .insertAsync(
                StorageChange(
                    timestamp = depositTs,
                    storageId = storageId,
                    action = StorageChangeAction.DEPOSIT,
                    item = item.withAmount(1),
                    amount = item.amount(),
                    slot = slot,
                    playerUuid = playerUuid,
                    playerName = playerName,
                    source = LogMetadata.VANILLA,
                    origin = LogMetadata.VANILLA,
                ),
            ).get(5, TimeUnit.SECONDS)
        instance.setBlock(position, liveBarrel)
        Storage.register(key, liveContents)

        fun assertAir() {
            assertEquals(Block.AIR, instance.getBlock(position))
            assertNull(Storage.barrels[key])
        }

        fun assertFilledBarrel() {
            val block = instance.getBlock(position)
            assertEquals(Block.BARREL.key(), block.key())
            assertEquals(item, Storage.barrels[key]?.inventory?.getItemStack(slot))
            assertEquals(item, StorageDeserializer.deserialize(block.nbtOrEmpty()).inventory.getItemStack(slot))
        }

        val params = LookupParams(users = listOf(playerName), since = targetTs, global = true)
        val selection = RollbackSelection(setOf(RollbackDomain.BLOCK, RollbackDomain.STORAGE))
        val actor = RollbackActor(UUID.randomUUID(), "combined-barrel-operator")

        try {
            val rollbackPlan =
                Logger.rollbackService
                    .computePlanAsync(
                        RollbackOperationKind.ROLLBACK,
                        params,
                        targetTs,
                        instance.uuid,
                        position,
                        safeMode = true,
                        selection = selection,
                    ).get(5, TimeUnit.SECONDS)
            assertEquals(2, rollbackPlan.totalChangeCount)

            val rollback = Logger.rollbackService.applyAsync(actor, rollbackPlan).get(5, TimeUnit.SECONDS)
            assertEquals(2, rollback.appliedCount)
            assertEquals(0, rollback.skippedCount)
            assertAir()

            val undoRollback = Logger.rollbackService.undoAsync(actor).get(5, TimeUnit.SECONDS)
            assertEquals(2, undoRollback.appliedCount)
            assertFilledBarrel()

            val redoRollback = Logger.rollbackService.redoAsync(actor).get(5, TimeUnit.SECONDS)
            assertEquals(2, redoRollback.appliedCount)
            assertAir()

            val restorePlan =
                Logger.rollbackService
                    .computePlanAsync(
                        RollbackOperationKind.RESTORE,
                        params,
                        targetTs,
                        instance.uuid,
                        position,
                        safeMode = true,
                        selection = selection,
                    ).get(5, TimeUnit.SECONDS)
            assertEquals(2, restorePlan.totalChangeCount)

            val restore = Logger.rollbackService.applyAsync(actor, restorePlan).get(5, TimeUnit.SECONDS)
            assertEquals(2, restore.appliedCount)
            assertEquals(0, restore.skippedCount)
            assertFilledBarrel()

            val undoRestore = Logger.rollbackService.undoAsync(actor).get(5, TimeUnit.SECONDS)
            assertEquals(2, undoRestore.appliedCount)
            assertAir()

            val redoRestore = Logger.rollbackService.redoAsync(actor).get(5, TimeUnit.SECONDS)
            assertEquals(2, redoRestore.appliedCount)
            assertFilledBarrel()
        } finally {
            Storage.remove(key)
            instance.setBlock(position, Block.AIR)
        }
    }

    @Test
    @Order(10)
    fun `safe and force combined barrel rollback preserve unrelated contents`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(2, 2).get(5, TimeUnit.SECONDS)
        val position = Pos(41.0, 40.0, 40.0)
        val key = Storage.keyFor(instance, position)
        val storageId = VanillaStorage.storageId(key)
        val selectedSlot = 18
        val unrelatedSlot = 19
        val selectedItem = ItemStack.of(Material.EMERALD, 6)
        val unrelatedItem = ItemStack.of(Material.GOLD_INGOT, 4)
        val playerUuid = UUID.randomUUID()
        val playerName = "combined-barrel-conflict-test"
        val targetTs = System.currentTimeMillis() - 10_000
        val placementTs = System.currentTimeMillis() - 2
        val emptyBarrel = Storage.withContents(Block.BARREL, StorageContents())
        val liveContents =
            StorageContents().also {
                it.inventory.setItemStack(selectedSlot, selectedItem)
                it.inventory.setItemStack(unrelatedSlot, unrelatedItem)
            }

        Logger.repository
            .insertAsync(
                blockEntry(placementTs, playerUuid, instance.uuid, position, Block.AIR, emptyBarrel).copy(
                    playerName = playerName,
                    blockOldNbt = ItemCodec.encodeBlockNbt(Block.AIR.nbt()),
                    blockNewNbt = ItemCodec.encodeBlockNbt(emptyBarrel.nbt()),
                ),
            ).get(5, TimeUnit.SECONDS)
        Logger.storageChange
            .insertAsync(
                StorageChange(
                    timestamp = placementTs + 1,
                    storageId = storageId,
                    action = StorageChangeAction.DEPOSIT,
                    item = selectedItem.withAmount(1),
                    amount = selectedItem.amount(),
                    slot = selectedSlot,
                    playerUuid = playerUuid,
                    playerName = playerName,
                    source = LogMetadata.VANILLA,
                    origin = LogMetadata.VANILLA,
                ),
            ).get(5, TimeUnit.SECONDS)
        instance.setBlock(position, Storage.withContents(Block.BARREL, liveContents))
        Storage.register(key, liveContents)

        fun assertExactContents() {
            val block = instance.getBlock(position)
            val liveInventory = requireNotNull(Storage.barrels[key]).inventory
            val serializedInventory = StorageDeserializer.deserialize(block.nbtOrEmpty()).inventory
            assertEquals(Block.BARREL.key(), block.key())
            assertEquals(selectedItem, liveInventory.getItemStack(selectedSlot))
            assertEquals(unrelatedItem, liveInventory.getItemStack(unrelatedSlot))
            assertEquals(selectedItem, serializedInventory.getItemStack(selectedSlot))
            assertEquals(unrelatedItem, serializedInventory.getItemStack(unrelatedSlot))
            assertEquals(
                setOf(selectedSlot, unrelatedSlot),
                (0 until liveInventory.size).filterNot { liveInventory.getItemStack(it).isAir }.toSet(),
            )
            assertEquals(
                setOf(selectedSlot, unrelatedSlot),
                (0 until serializedInventory.size).filterNot { serializedInventory.getItemStack(it).isAir }.toSet(),
            )
        }

        val params = LookupParams(users = listOf(playerName), since = targetTs, global = true)
        val selection = RollbackSelection(setOf(RollbackDomain.BLOCK, RollbackDomain.STORAGE))

        try {
            val safePlan =
                Logger.rollbackService
                    .computePlanAsync(
                        RollbackOperationKind.ROLLBACK,
                        params,
                        targetTs,
                        instance.uuid,
                        position,
                        safeMode = true,
                        selection = selection,
                    ).get(5, TimeUnit.SECONDS)
            val safeResult =
                Logger.rollbackService
                    .applyAsync(RollbackActor(UUID.randomUUID(), "safe-barrel-operator"), safePlan)
                    .get(5, TimeUnit.SECONDS)
            assertEquals(0, safeResult.appliedCount)
            assertEquals(2, safeResult.skippedCount)
            assertExactContents()

            val forcePlan =
                Logger.rollbackService
                    .computePlanAsync(
                        RollbackOperationKind.ROLLBACK,
                        params,
                        targetTs,
                        instance.uuid,
                        position,
                        safeMode = false,
                        selection = selection,
                    ).get(5, TimeUnit.SECONDS)
            val forceActor = RollbackActor(UUID.randomUUID(), "force-barrel-operator")
            val forceResult = Logger.rollbackService.applyAsync(forceActor, forcePlan).get(5, TimeUnit.SECONDS)
            assertEquals(2, forceResult.appliedCount)
            assertEquals(0, forceResult.skippedCount)
            assertEquals(Block.AIR, instance.getBlock(position))
            assertNull(Storage.barrels[key])

            val undoForce = Logger.rollbackService.undoAsync(forceActor).get(5, TimeUnit.SECONDS)
            assertEquals(2, undoForce.appliedCount)
            assertExactContents()
        } finally {
            Storage.remove(key)
            instance.setBlock(position, Block.AIR)
        }
    }

    @Test
    @Order(11)
    fun `block logging observes final event outcome`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(0, 0).get(5, TimeUnit.SECONDS)
        val player = createPlayer(instance, Pos(0.5, 42.0, 0.5), "block-state-test")
        val cancelledPlacePosition = BlockVec(3, 41, 3)
        val mutatedPlacePosition = BlockVec(4, 41, 4)
        val mutatedBreakPosition = BlockVec(5, 41, 5)
        val cancelledInteractPosition = BlockVec(6, 41, 6)
        val cancelledBreakPosition = BlockVec(7, 41, 7)
        val manualBreakPosition = BlockVec(8, 41, 8)
        val finalPlaceNbt = CompoundBinaryTag.builder().putString("logger-test", "final-place").build()
        val finalPlaceBlock = Block.BARREL.withNbt(finalPlaceNbt)
        val finalBreakNbt = CompoundBinaryTag.builder().putString("logger-test", "final-break").build()
        val finalBreakBlock = Block.GOLD_BLOCK.withNbt(finalBreakNbt)

        instance.setBlock(mutatedPlacePosition, Block.STONE)
        instance.setBlock(mutatedBreakPosition, Block.DIAMOND_BLOCK)
        instance.setBlock(cancelledInteractPosition, Block.OAK_DOOR)
        instance.setBlock(cancelledBreakPosition, Block.EMERALD_BLOCK)
        instance.setBlock(manualBreakPosition, Block.IRON_BLOCK)

        val mutatorNode = EventNode.all("logger-block-final-state-test").setPriority(0)
        mutatorNode.addListener(PlayerBlockPlaceEvent::class.java) { event ->
            when (event.blockPosition) {
                cancelledPlacePosition -> event.isCancelled = true
                mutatedPlacePosition -> event.block = finalPlaceBlock
            }
        }
        mutatorNode.addListener(PlayerBlockBreakEvent::class.java) { event ->
            when (event.blockPosition) {
                mutatedBreakPosition -> event.resultBlock = finalBreakBlock
                cancelledBreakPosition -> event.isCancelled = true
                manualBreakPosition -> {
                    event.instance.setBlock(event.blockPosition, Block.AIR)
                    event.isCancelled = true
                }
            }
        }
        mutatorNode.addListener(PlayerBlockInteractEvent::class.java) { event ->
            if (event.blockPosition == cancelledInteractPosition) event.isCancelled = true
        }
        MinecraftServer.getGlobalEventHandler().addChild(mutatorNode)

        try {
            fun callPlace(position: BlockVec) {
                MinecraftServer.getGlobalEventHandler().call(
                    PlayerBlockPlaceEvent(player, instance, Block.DIRT, BlockFace.TOP, position, Pos.ZERO, PlayerHand.MAIN),
                )
            }

            fun callBreak(
                position: BlockVec,
                block: Block,
            ) {
                MinecraftServer.getGlobalEventHandler().call(
                    PlayerBlockBreakEvent(player, instance, block, Block.AIR, position, BlockFace.TOP),
                )
            }

            callPlace(cancelledPlacePosition)
            callPlace(mutatedPlacePosition)
            callBreak(mutatedBreakPosition, Block.DIAMOND_BLOCK)
            MinecraftServer.getGlobalEventHandler().call(
                PlayerBlockInteractEvent(
                    player,
                    PlayerHand.MAIN,
                    instance,
                    Block.OAK_DOOR,
                    cancelledInteractPosition,
                    Pos.ZERO,
                    BlockFace.TOP,
                ),
            )
            callBreak(cancelledBreakPosition, Block.EMERALD_BLOCK)
            callBreak(manualBreakPosition, Block.IRON_BLOCK)
            Logger.repository.flushAsync().get(5, TimeUnit.SECONDS)

            val cancelledPlaceEntries = blockEntriesAt(cancelledPlacePosition)
            val mutatedPlaceEntry = blockEntriesAt(mutatedPlacePosition).single()
            val mutatedBreakEntry = blockEntriesAt(mutatedBreakPosition).single()
            val cancelledInteractEntries = blockEntriesAt(cancelledInteractPosition)
            val cancelledBreakEntries = blockEntriesAt(cancelledBreakPosition)
            val manualBreakEntry = blockEntriesAt(manualBreakPosition).single()

            assertEquals(0, cancelledPlaceEntries.size)
            assertEquals(BlockAction.PLACE, mutatedPlaceEntry.action)
            assertEquals(Block.STONE.key().asString(), mutatedPlaceEntry.blockOld)
            assertEquals(finalPlaceBlock.key().asString(), mutatedPlaceEntry.blockNew)
            assertEquals(finalPlaceBlock.state(), mutatedPlaceEntry.blockNewState)
            assertEquals(finalPlaceNbt, ItemCodec.decodeBlockNbt(mutatedPlaceEntry.blockNewNbt))
            assertEquals(BlockAction.BREAK, mutatedBreakEntry.action)
            assertEquals(Block.DIAMOND_BLOCK.key().asString(), mutatedBreakEntry.blockOld)
            assertEquals(finalBreakBlock.key().asString(), mutatedBreakEntry.blockNew)
            assertEquals(finalBreakBlock.state(), mutatedBreakEntry.blockNewState)
            assertEquals(finalBreakNbt, ItemCodec.decodeBlockNbt(mutatedBreakEntry.blockNewNbt))
            assertEquals(0, cancelledInteractEntries.size)
            assertEquals(0, cancelledBreakEntries.size)
            assertEquals(Block.AIR, instance.getBlock(manualBreakPosition))
            assertEquals(BlockAction.BREAK, manualBreakEntry.action)
            assertEquals(Block.IRON_BLOCK.key().asString(), manualBreakEntry.blockOld)
            assertEquals(Block.AIR.key().asString(), manualBreakEntry.blockNew)
            assertEquals(Block.AIR.state(), manualBreakEntry.blockNewState)
        } finally {
            MinecraftServer.getGlobalEventHandler().removeChild(mutatorNode)
            player.remove()
        }
    }

    @Test
    @Order(12)
    fun `combat explosions log damage and fire for rollback`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(0, 0).get(5, TimeUnit.SECONDS)
        val position = BlockVec(2, 41, 2)
        val support = BlockVec(2, 40, 2)
        val started = System.currentTimeMillis() - 1_000
        instance.setBlock(position, Block.STONE)
        instance.setBlock(support, Block.STONE)

        Explosion(
            instance = instance,
            pos = Pos(position.x(), position.y(), position.z()),
            radius = 0,
            fire = 1.0,
        )

        val deadline = System.currentTimeMillis() + 5_000
        while (instance.getBlock(position) != Block.FIRE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(Block.FIRE, instance.getBlock(position))
        Logger.repository.flushAsync().get(5, TimeUnit.SECONDS)

        val entries = blockEntriesAt(position)
        assertEquals(2, entries.size)
        assertEquals(BlockAction.PLACE, entries[0].action)
        assertEquals(BlockAction.BREAK, entries[1].action)
        assertEquals(LogMetadata.COMBAT, entries[0].source)
        assertEquals(LogMetadata.COMBAT, entries[0].origin)
        assertEquals(Block.STONE.key().asString(), entries[1].blockOld)
        assertEquals(Block.AIR.key().asString(), entries[1].blockNew)
        assertEquals(Block.AIR.key().asString(), entries[0].blockOld)
        assertEquals(Block.FIRE.key().asString(), entries[0].blockNew)

        val params =
            LookupParams(
                users = listOf(LogMetadata.COMBAT),
                source = LogMetadata.COMBAT,
                since = started,
                radius = 2,
            )
        val actor = RollbackActor(UUID.randomUUID(), "combat-rollback-operator")
        val rollbackPlan =
            Logger.rollbackService
                .computePlanAsync(
                    RollbackOperationKind.ROLLBACK,
                    params,
                    started,
                    instance.uuid,
                    Pos(position.x(), position.y(), position.z()),
                    safeMode = true,
                ).get(5, TimeUnit.SECONDS)
        val rollbackResult = Logger.rollbackService.applyAsync(actor, rollbackPlan).get(5, TimeUnit.SECONDS)
        assertEquals(2, rollbackResult.appliedCount)
        assertEquals(Block.STONE, instance.getBlock(position))

        val restorePlan =
            Logger.rollbackService
                .computePlanAsync(
                    RollbackOperationKind.RESTORE,
                    params,
                    started,
                    instance.uuid,
                    Pos(position.x(), position.y(), position.z()),
                    safeMode = true,
                ).get(5, TimeUnit.SECONDS)
        val restoreResult = Logger.rollbackService.applyAsync(actor, restorePlan).get(5, TimeUnit.SECONDS)
        assertEquals(2, restoreResult.appliedCount)
        assertEquals(Block.FIRE, instance.getBlock(position))
    }

    @Test
    @Order(13)
    fun `close persists pending log entries`() {
        repeat(100) { index ->
            Logger.log(
                FeatureLogEntry(
                    timestamp = System.currentTimeMillis(),
                    playerUuid = null,
                    playerName = null,
                    source = "shutdown-test",
                    action = "insert-$index",
                    summary = "pending log entry $index",
                ),
            )
        }

        if (System.getProperty("keepRunning") == "true") return

        Logger.close()

        val jdbcPath = databasePath.toString().replace('\\', '/')
        DriverManager.getConnection("jdbc:h2:file:$jdbcPath;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM \"feature_log\" WHERE source = 'shutdown-test'").use { results ->
                    results.next()
                    assertEquals(100, results.getInt(1))
                }
            }
        }
    }

    private fun blockEntry(
        timestamp: Long,
        playerUuid: UUID,
        instanceUuid: UUID,
        position: Pos,
        old: Block,
        new: Block,
    ): BlockLogEntry =
        BlockLogEntry(
            timestamp = timestamp,
            playerUuid = playerUuid,
            playerName = "rollback-test",
            x = position.blockX(),
            y = position.blockY(),
            z = position.blockZ(),
            blockOld = old.key().asString(),
            blockNew = new.key().asString(),
            action = BlockAction.PLACE,
            instanceUuid = instanceUuid,
            blockOldState = old.state(),
            blockNewState = new.state(),
        )

    private fun blockEntriesAt(position: BlockVec): List<BlockLogEntry> =
        Logger.repository
            .lookupAsync(position.blockX(), position.blockY(), position.blockZ())
            .get(5, TimeUnit.SECONDS)

    private fun createPlayer(
        instance: net.minestom.server.instance.Instance,
        position: Pos,
        username: String,
    ): Player {
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), username))
        player.setInstance(instance, position).get(5, TimeUnit.SECONDS)
        return player
    }

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    @AfterAll
    fun keepRunning() {
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
        Logger.close()
    }
}
