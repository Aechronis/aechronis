package net.aechronis.logger.utils

import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Metadata
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.attribute.AttributeInstance
import net.minestom.server.entity.metadata.LivingEntityMeta
import net.minestom.server.item.ItemStack
import net.minestom.server.network.NetworkBuffer
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.potion.Potion
import java.util.UUID

/**
 * Persists the common state needed to reconstruct logged entities.
 *
 * The envelope remains in the existing `tag_data` blob. Blobs written before
 * the envelope was introduced are treated as the entity's custom tags.
 * Application-specific subclasses and AI groups cannot be serialized by
 * Minestom, so they restore as the closest standard entity class.
 */
internal object EntityStateCodec {
    private const val VERSION_KEY = "__aechronis_logger_entity_state_version"
    private const val CURRENT_VERSION = 1
    private const val KIND_KEY = "kind"
    private const val TAGS_KEY = "tags"
    private const val METADATA_KEY = "metadata"
    private const val ATTRIBUTES_KEY = "attributes"
    private const val EFFECTS_KEY = "effects"
    private const val EQUIPMENT_KEY = "equipment"
    private const val HEALTH_KEY = "health"
    private const val INVULNERABLE_KEY = "invulnerable"
    private const val FIRE_TICKS_KEY = "fire_ticks"
    private const val ARROW_COUNT_KEY = "arrow_count"
    private const val CAN_PICKUP_ITEM_KEY = "can_pickup_item"
    private const val HAS_PHYSICS_KEY = "has_physics"
    private const val AUTO_VIEWABLE_KEY = "auto_viewable"
    private const val BOUNDING_WIDTH_KEY = "bounding_width"
    private const val BOUNDING_HEIGHT_KEY = "bounding_height"
    private const val BOUNDING_DEPTH_KEY = "bounding_depth"
    private const val CREATURE_REMOVAL_DELAY_KEY = "creature_removal_delay"

    private val attributeListType = AttributeInstance.NETWORK_TYPE.list(Short.MAX_VALUE.toInt())
    private val potionListType = Potion.NETWORK_TYPE.list(Short.MAX_VALUE.toInt())
    private val missingMetadataValue = Any()

    internal enum class Kind(
        val value: String,
    ) {
        ENTITY("entity"),
        LIVING("living"),
        CREATURE("creature"),
        ;

        companion object {
            fun fromValue(value: String): Kind = entries.firstOrNull { it.value == value } ?: ENTITY
        }
    }

    internal data class State(
        val kind: Kind? = null,
        val tags: CompoundBinaryTag = CompoundBinaryTag.empty(),
        val metadataData: ByteArray? = null,
        val attributeData: ByteArray? = null,
        val effectData: ByteArray? = null,
        val equipment: Map<EquipmentSlot, ItemStack> = emptyMap(),
        val health: Float? = null,
        val invulnerable: Boolean = false,
        val fireTicks: Int = 0,
        val arrowCount: Int = 0,
        val canPickupItem: Boolean = false,
        val hasPhysics: Boolean = true,
        val autoViewable: Boolean = true,
        val boundingWidth: Double? = null,
        val boundingHeight: Double? = null,
        val boundingDepth: Double? = null,
        val creatureRemovalDelay: Int? = null,
    )

    fun encode(entity: Entity): ByteArray {
        val process = MinecraftServer.process()
        val metadataPacket = entity.metadataPacket
        val metadataData = NetworkBuffer.makeArray(EntityMetaDataPacket.SERIALIZER, metadataPacket, process)
        val effects =
            entity.activeEffects.mapNotNull { timedPotion ->
                val potion = timedPotion.potion()
                if (potion.duration() == Potion.INFINITE_DURATION) return@mapNotNull potion
                val elapsed = (entity.aliveTicks - timedPotion.startingTicks()).coerceAtLeast(0)
                val remaining = potion.duration() - elapsed.toInt()
                if (remaining <= 0) null else Potion(potion.effect(), potion.amplifier(), remaining, potion.flags())
            }
        val effectData = NetworkBuffer.makeArray(potionListType, effects, process)
        val boundingBox = entity.boundingBox

        val builder =
            CompoundBinaryTag
                .builder()
                .putInt(VERSION_KEY, CURRENT_VERSION)
                .putString(KIND_KEY, kindOf(entity).value)
                .put(TAGS_KEY, entity.tagHandler().asCompound())
                .putByteArray(METADATA_KEY, metadataData)
                .putByteArray(EFFECTS_KEY, effectData)
                .putByte(HAS_PHYSICS_KEY, entity.hasPhysics().toByte())
                .putByte(AUTO_VIEWABLE_KEY, entity.isAutoViewable.toByte())
                .putDouble(BOUNDING_WIDTH_KEY, boundingBox.width())
                .putDouble(BOUNDING_HEIGHT_KEY, boundingBox.height())
                .putDouble(BOUNDING_DEPTH_KEY, boundingBox.depth())

        if (entity is LivingEntity) {
            val equipment = CompoundBinaryTag.builder()
            for (slot in EquipmentSlot.entries) {
                val item = entity.getEquipment(slot)
                if (!item.isAir) equipment.put(slot.name, item.toItemNBT(process))
            }
            val attributeData = NetworkBuffer.makeArray(attributeListType, entity.attributes.toList(), process)
            builder
                .put(EQUIPMENT_KEY, equipment.build())
                .putByteArray(ATTRIBUTES_KEY, attributeData)
                .putFloat(HEALTH_KEY, entity.health)
                .putByte(INVULNERABLE_KEY, entity.isInvulnerable.toByte())
                .putInt(FIRE_TICKS_KEY, entity.fireTicks)
                .putInt(ARROW_COUNT_KEY, entity.arrowCount)
                .putByte(CAN_PICKUP_ITEM_KEY, entity.canPickupItem().toByte())
        }
        if (entity is EntityCreature) {
            builder.putInt(CREATURE_REMOVAL_DELAY_KEY, entity.removalAnimationDelay)
        }
        return ItemCodec.encodeBlockNbt(builder.build())!!
    }

    fun decode(data: ByteArray?): State {
        val root = runCatching { ItemCodec.decodeBlockNbt(data) }.getOrNull() ?: return State()
        if (!root.contains(VERSION_KEY)) return State(tags = root)

        val tags = root.getCompound(TAGS_KEY)
        if (root.getInt(VERSION_KEY) != CURRENT_VERSION) return State(tags = tags)

        val equipmentTag = root.getCompound(EQUIPMENT_KEY)
        val equipment =
            EquipmentSlot.entries
                .mapNotNull { slot ->
                    if (!equipmentTag.contains(slot.name)) return@mapNotNull null
                    val item =
                        runCatching { ItemStack.fromItemNBT(equipmentTag.getCompound(slot.name), MinecraftServer.process()) }
                            .getOrNull()
                            ?: return@mapNotNull null
                    slot to item
                }.toMap()
        val kind = Kind.fromValue(root.getString(KIND_KEY))
        return State(
            kind = kind,
            tags = tags,
            metadataData = root.byteArrayOrNull(METADATA_KEY),
            attributeData = root.byteArrayOrNull(ATTRIBUTES_KEY),
            effectData = root.byteArrayOrNull(EFFECTS_KEY),
            equipment = equipment,
            health = if (kind == Kind.ENTITY || !root.contains(HEALTH_KEY)) null else root.getFloat(HEALTH_KEY),
            invulnerable = root.getByte(INVULNERABLE_KEY).toInt() != 0,
            fireTicks = root.getInt(FIRE_TICKS_KEY),
            arrowCount = root.getInt(ARROW_COUNT_KEY),
            canPickupItem = root.getByte(CAN_PICKUP_ITEM_KEY).toInt() != 0,
            hasPhysics = !root.contains(HAS_PHYSICS_KEY) || root.getByte(HAS_PHYSICS_KEY).toInt() != 0,
            autoViewable = !root.contains(AUTO_VIEWABLE_KEY) || root.getByte(AUTO_VIEWABLE_KEY).toInt() != 0,
            boundingWidth = root.doubleOrNull(BOUNDING_WIDTH_KEY),
            boundingHeight = root.doubleOrNull(BOUNDING_HEIGHT_KEY),
            boundingDepth = root.doubleOrNull(BOUNDING_DEPTH_KEY),
            creatureRemovalDelay =
                if (kind == Kind.CREATURE && root.contains(CREATURE_REMOVAL_DELAY_KEY)) {
                    root.getInt(CREATURE_REMOVAL_DELAY_KEY)
                } else {
                    null
                },
        )
    }

    fun create(
        type: EntityType,
        uuid: UUID,
        state: State,
    ): Entity {
        val storedKind = state.kind
        if (storedKind == null) {
            val baseEntity = Entity(type, uuid)
            return if (baseEntity.entityMeta is LivingEntityMeta) LivingEntity(type, uuid) else baseEntity
        }
        return when (storedKind) {
            Kind.ENTITY -> Entity(type, uuid)
            Kind.LIVING -> LivingEntity(type, uuid)
            Kind.CREATURE -> EntityCreature(type, uuid)
        }
    }

    fun restore(
        entity: Entity,
        state: State,
    ) {
        entity.tagHandler().updateContent(state.tags)
        entity.setHasPhysics(state.hasPhysics)
        entity.isAutoViewable = state.autoViewable
        restoreEffects(entity, state.effectData)

        if (entity is LivingEntity) {
            state.equipment.forEach(entity::setEquipment)
            restoreAttributes(entity, state.attributeData)
            entity.isInvulnerable = state.invulnerable
            entity.fireTicks = state.fireTicks
            entity.arrowCount = state.arrowCount
            entity.setCanPickupItem(state.canPickupItem)
            state.health?.let(entity::setHealth)
        }
        if (entity is EntityCreature) {
            state.creatureRemovalDelay?.let { entity.removalAnimationDelay = it }
        }
        restoreMetadata(entity, state.metadataData)
        if (state.boundingWidth != null && state.boundingHeight != null && state.boundingDepth != null) {
            entity.setBoundingBox(state.boundingWidth, state.boundingHeight, state.boundingDepth)
        }
    }

    private fun restoreAttributes(
        entity: LivingEntity,
        data: ByteArray?,
    ) {
        val attributes = decodeNetwork(attributeListType, data) ?: return
        for (stored in attributes) {
            val target = entity.getAttribute(stored.attribute())
            target.clearModifiers()
            target.baseValue = stored.baseValue
            stored.modifiers().forEach(target::addModifier)
        }
    }

    private fun restoreEffects(
        entity: Entity,
        data: ByteArray?,
    ) {
        val effects = decodeNetwork(potionListType, data) ?: return
        effects.forEach(entity::addEffect)
    }

    private fun restoreMetadata(
        entity: Entity,
        data: ByteArray?,
    ) {
        val packet = decodeNetwork(EntityMetaDataPacket.SERIALIZER, data) ?: return
        packet.entries().forEach { (index, entry) -> restoreMetadataEntry(entity, index, entry) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreMetadataEntry(
        entity: Entity,
        index: Int,
        rawEntry: Metadata.Entry<*>,
    ) {
        val entry = rawEntry as Metadata.Entry<Any>
        val definition = MetadataDef.Entry.Index(index, { entry }, missingMetadataValue)
        entity.entityMeta.set(definition, entry.value())
    }

    private fun <T> decodeNetwork(
        type: NetworkBuffer.Type<T>,
        data: ByteArray?,
    ): T? {
        if (data == null) return null
        return runCatching {
            NetworkBuffer
                .wrap(data, 0, data.size, MinecraftServer.process())
                .read(type)
        }.getOrNull()
    }

    private fun kindOf(entity: Entity): Kind =
        when (entity) {
            is EntityCreature -> Kind.CREATURE
            is LivingEntity -> Kind.LIVING
            else -> Kind.ENTITY
        }

    private fun Boolean.toByte(): Byte = if (this) 1 else 0

    private fun CompoundBinaryTag.byteArrayOrNull(key: String): ByteArray? = if (contains(key)) getByteArray(key) else null

    private fun CompoundBinaryTag.doubleOrNull(key: String): Double? = if (contains(key)) getDouble(key) else null
}
