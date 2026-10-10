package net.aechronis.nodes.permissions

import net.aechronis.nodes.constants.TownPermissions

internal enum class LandAccessAction(
    val permission: TownPermissions,
    val checksProtectedChest: Boolean = false,
    val occupierCanGrant: Boolean = true,
) {
    BREAK(TownPermissions.DESTROY),
    PLACE(TownPermissions.BUILD),
    INTERACT(TownPermissions.INTERACT),
    CHEST_INTERACT(TownPermissions.CHESTS, checksProtectedChest = true, occupierCanGrant = false),
    STORAGE_INTERACT(TownPermissions.CHESTS, checksProtectedChest = true),
    STORAGE_BREAK(TownPermissions.DESTROY, checksProtectedChest = true),
    ;

    val isBlockInteraction: Boolean get() = this == INTERACT || this == CHEST_INTERACT
    val allowsUnclaimed: Boolean get() = isBlockInteraction || this == STORAGE_INTERACT

    val denial: LandAccessDecision
        get() = when (this) {
            BREAK, STORAGE_BREAK -> LandAccessDecision.DENY_DESTROY
            PLACE -> LandAccessDecision.DENY_BUILD
            INTERACT -> LandAccessDecision.DENY_INTERACT
            CHEST_INTERACT, STORAGE_INTERACT -> LandAccessDecision.DENY_CHESTS
        }
}

internal enum class LandAccessDecision(
    val message: String? = null,
    val applyPlacementCooldown: Boolean = false,
) {
    ALLOW,
    DENY_DESTROY("You cannot destroy here!"),
    DENY_BUILD("You cannot build here!"),
    DENY_BUILD_WITH_COOLDOWN("You cannot build here!", applyPlacementCooldown = true),
    DENY_INTERACT("You cannot interact here!"),
    DENY_CHESTS("You cannot use chests here!"),
    DENY_PROTECTED_CHEST("This chest is for trusted residents only"),
}

/** Queries are lazy so an earlier decision does not inspect unrelated permission paths. */
internal interface LandAccessContext {
    val hasTown: Boolean
    val hasResident: Boolean
    val wildernessAllowed: Boolean
    val bypass: Boolean
    val interactiveBlock: Boolean

    /** Null means there is no settled warzone occupier; false is a terminal denial. */
    val warzonePermission: Boolean?
    val warAllowed: Boolean
    val plotPermission: Boolean?
    val townAllowed: Boolean
    val occupierAllowed: Boolean
    val protectedChestAllowed: Boolean
    val flagPlacementAllowed: Boolean
}

internal object LandAccessPolicy {
    fun evaluate(action: LandAccessAction, context: LandAccessContext): LandAccessDecision {
        if (!context.hasTown) {
            return if (action.allowsUnclaimed || context.wildernessAllowed) LandAccessDecision.ALLOW else action.denial
        }
        if (!context.hasResident) {
            return when {
                action == LandAccessAction.PLACE -> LandAccessDecision.DENY_BUILD_WITH_COOLDOWN
                action.isBlockInteraction -> LandAccessDecision.DENY_INTERACT
                else -> action.denial
            }
        }
        if (context.bypass) return LandAccessDecision.ALLOW
        if (action.isBlockInteraction && !context.interactiveBlock) return LandAccessDecision.ALLOW

        // Settled warzones use only the capturing town, before active-war or plot grants.
        context.warzonePermission?.let { allowed ->
            return if (allowed) checkProtectedChest(action, context) else action.denial
        }
        if (context.warAllowed) return LandAccessDecision.ALLOW

        val plotPermission = context.plotPermission
        val allowed = plotPermission ?: (
            context.townAllowed || (action.occupierCanGrant && context.occupierAllowed)
            )
        if (allowed) return checkProtectedChest(action, context)

        // Only the ordinary placement fallback accepts flags or applies a cooldown.
        // Wilderness, warzone and explicit plot denials return without either exception.
        if (action == LandAccessAction.PLACE && plotPermission == null) {
            return if (context.flagPlacementAllowed) LandAccessDecision.ALLOW else LandAccessDecision.DENY_BUILD_WITH_COOLDOWN
        }
        return action.denial
    }

    private fun checkProtectedChest(action: LandAccessAction, context: LandAccessContext): LandAccessDecision = if (action.checksProtectedChest && !context.protectedChestAllowed) {
        LandAccessDecision.DENY_PROTECTED_CHEST
    } else {
        LandAccessDecision.ALLOW
    }
}
