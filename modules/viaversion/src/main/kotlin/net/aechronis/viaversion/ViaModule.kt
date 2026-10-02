package net.aechronis.viaversion

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import java.nio.file.Path

class ViaModule : AechronisModule {
    override val id = "viaversion"

    private var transport: ViaServer? = null
    private var registration: AutoCloseable? = null

    override fun initialize(context: ModuleContext) {
        check(transport == null) { "ViaVersion is already initialized" }
        val via = ViaServer(Path.of(System.getProperty("aechronis.viaversion.directory", "viaversion")))
        transport = via
        registration = context.installServerTransport(via)
    }

    override fun prepareForShutdown(context: ModuleContext) {
        transport?.quiesce()
    }

    override fun shutdown(context: ModuleContext) {
        registration?.close()
        registration = null
        // Also clean up when installing the transport failed before registration completed.
        transport?.close()
        transport = null
    }
}
