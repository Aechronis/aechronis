package net.aechronis.server.network

import net.minestom.server.MinecraftServer
import java.net.InetSocketAddress

/** The core owns the listen address; a module owns each generation of the transport. */
class ServerNetwork(
    private var address: InetSocketAddress,
) : AutoCloseable {
    private var transport: ServerTransport? = null
    private var started = false
    private var closed = false

    @Synchronized
    fun install(replacement: ServerTransport): AutoCloseable {
        check(!closed && transport == null) { "A server transport is already installed or the network is closed" }
        if (started) address = replacement.bind(address)
        transport = replacement
        return AutoCloseable {
            synchronized(this) {
                if (transport === replacement) {
                    replacement.close()
                    transport = null
                }
            }
        }
    }

    @Synchronized
    fun start(server: MinecraftServer): InetSocketAddress {
        check(!closed && !started) { "The server network has already started or closed" }
        // Minestom starts its ticker alongside its built-in listener. Only the module's
        // listener accepts public connections; the bootstrap socket is immediately closed.
        server.start("127.0.0.1", 0)
        MinecraftServer.getServer().stop()
        started = true
        transport?.let { address = it.bind(address) }
        return address
    }

    @Synchronized
    fun stopAccepting() {
        transport?.stopAccepting()
    }

    @Synchronized
    override fun close() {
        closed = true
        transport?.close()
        transport = null
    }
}

interface ServerTransport : AutoCloseable {
    fun bind(address: InetSocketAddress): InetSocketAddress

    fun stopAccepting()
}

/** Connections with their own buffered-packet event dispatch, regardless of the providing module. */
interface BufferedPacketEventConnection
