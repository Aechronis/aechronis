package net.aechronis.guard.objects

import net.aechronis.guard.flags.BooleanFlagValue
import net.aechronis.guard.flags.FlagName

class ZonePolicy(
    private val defaults: Map<FlagName, BooleanFlagValue>,
) {
    fun allows(
        zone: Zone?,
        flag: FlagName,
    ): Boolean {
        val default = defaults[flag]?.value ?: true
        return (zone?.flags?.get(flag) as? BooleanFlagValue)?.value ?: default
    }
}
