package net.aechronis.utils

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class UtilsModule : AechronisModule {
    override val id = "utils"

    override fun shutdown(context: ModuleContext) = VisibilityRules.clearAll()
}
