package net.aechronis.viaversion

import com.viaversion.viaversion.ViaManagerImpl
import com.viaversion.viaversion.api.Via
import com.viaversion.viaversion.exception.CancelCodecException
import com.viaversion.viaversion.platform.ViaChannelInitializer
import com.viaversion.viaversion.platform.ViaDecodeHandler
import com.viaversion.viaversion.platform.ViaEncodeHandler
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPromise
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.util.concurrent.GlobalEventExecutor
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.network.ServerTransport
import net.minestom.server.MinecraftServer
import net.minestom.server.ServerFlag
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A direct Minestom transport in the same JVM, with no proxy or forwarding connection. */
class ViaServer(
    directory: Path,
) : ServerTransport {
    private val acceptor = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workers = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
    private val channels = DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true)
    private val players = ConcurrentHashMap.newKeySet<ViaPlayerConnection>()
    private val lifecycleLock = Any()
    private var quiescing = false
    private val closed = AtomicBoolean()
    private var initialized = false
    private var platform: ViaPlatform? = null
    private val events =
        EventNode.all("viaversion-configuration").setPriority(Int.MAX_VALUE).addListener(AsyncPlayerConfigurationEvent::class.java) {
            val connection = it.player.playerConnection
            if (connection is ViaPlayerConnection && connection.protocolVersion != MinecraftServer.PROTOCOL_VERSION) {
                // Via clears registry storage on configuration transitions. Minestom normally omits
                // unchanged registries after the initial login, leaving older translators without it.
                it.setSendRegistryData(true)
            }
        }

    @Volatile private var listener: Channel? = null

    init {
        try {
            check(!ServerFlag.PROXY_PROTOCOL) { "PROXY protocol is not supported by the Via transport" }
            check(!Via.isLoaded()) { "ViaVersion is already initialized in this JVM" }
            initialized = true
            ViaPlatform(directory.toFile()).also {
                platform = it
                it.initialize()
            }
            ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), events)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun bind(address: InetSocketAddress): InetSocketAddress {
        check(!closed.get() && this.listener == null) { "ViaVersion transport has already been started or closed" }
        val listener =
            ServerBootstrap()
                .group(acceptor, workers)
                .channel(AcceptChannel::class.java)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(
                    object : ChannelInitializer<TransportChannel>() {
                        override fun initChannel(channel: TransportChannel) {
                            synchronized(lifecycleLock) {
                                if (quiescing) {
                                    channel.close()
                                    return
                                }
                                channels.add(channel)
                                val compression =
                                    MinecraftWireCodec.Compression {
                                        channel.attr(ViaPlayerConnection.KEY)?.get()?.incomingLimit() ?: ServerFlag.MAX_PACKET_SIZE_PRE_AUTH
                                    }
                                val player = ViaPlayerConnection(channel, compression, players::remove)
                                players.add(player)
                                val connection = ViaChannelInitializer.createUserConnection(channel, false)
                                player.viaConnection = connection
                                channel
                                    .pipeline()
                                    .addLast("read-timeout", ReadTimeoutHandler(ServerFlag.SOCKET_TIMEOUT.toLong(), TimeUnit.MILLISECONDS))
                                    .addLast("frame-decoder", MinecraftWireCodec.FrameDecoder(player::incomingLimit))
                                    .addLast("frame-encoder", MinecraftWireCodec.FrameEncoder())
                                    .addLast("compression", compression)
                                    .addLast(
                                        "frame-queue",
                                        object : ChannelInboundHandlerAdapter() {
                                            override fun channelRead(
                                                context: ChannelHandlerContext,
                                                message: Any,
                                            ) {
                                                val packet = message as ByteBuf
                                                try {
                                                    player.receive(packet)
                                                } finally {
                                                    packet.release()
                                                }
                                            }
                                        },
                                    ).addLast(
                                        "grim-client",
                                        object : ChannelInboundHandlerAdapter() {
                                            override fun channelRead(
                                                context: ChannelHandlerContext,
                                                message: Any,
                                            ) {
                                                val packet = message as ByteBuf
                                                try {
                                                    player.packetListener?.receive(packet, true)
                                                } catch (error: Throwable) {
                                                    packet.release()
                                                    throw error
                                                }
                                                if (packet.isReadable) context.fireChannelRead(packet) else packet.release()
                                            }
                                        },
                                    ).addLast(ViaDecodeHandler.NAME, ViaDecodeHandler(connection))
                                    .addLast(ViaEncodeHandler.NAME, ViaEncodeHandler(connection))
                                    .addLast(
                                        "grim-server",
                                        object : ChannelDuplexHandler() {
                                            override fun channelRead(
                                                context: ChannelHandlerContext,
                                                message: Any,
                                            ) {
                                                val packet = message as ByteBuf
                                                try {
                                                    player.packetListener?.receive(packet, false)
                                                } catch (error: Throwable) {
                                                    packet.release()
                                                    throw error
                                                }
                                                if (packet.isReadable) context.fireChannelRead(packet) else packet.release()
                                            }

                                            override fun write(
                                                context: ChannelHandlerContext,
                                                message: Any,
                                                promise: ChannelPromise,
                                            ) {
                                                val packet = message as ByteBuf
                                                val after =
                                                    try {
                                                        player.packetListener?.send(packet)
                                                    } catch (error: Throwable) {
                                                        packet.release()
                                                        promise.setFailure(error)
                                                        return
                                                    }
                                                if (packet.isReadable) {
                                                    context.write(packet, promise)
                                                    after?.run()
                                                } else {
                                                    packet.release()
                                                    promise.setSuccess()
                                                }
                                            }
                                        },
                                    ).addLast(
                                        "minestom",
                                        object : SimpleChannelInboundHandler<ByteBuf>() {
                                            override fun channelRead0(
                                                context: ChannelHandlerContext,
                                                packet: ByteBuf,
                                            ) {
                                                player.receiveTranslated(packet)
                                            }

                                            override fun channelInactive(context: ChannelHandlerContext) {
                                                player.disconnect()
                                            }

                                            override fun exceptionCaught(
                                                context: ChannelHandlerContext,
                                                error: Throwable,
                                            ) {
                                                if (error is CancelCodecException) return
                                                if (error !is IOException) MinecraftServer.getExceptionManager().handleException(error)
                                                player.disconnect()
                                            }
                                        },
                                    )
                                player.startReader()
                            }
                        }
                    },
                ).bind(address.hostString, address.port)
                .sync()
                .channel()
        channels.add(listener)
        this.listener = listener
        MinecraftServer.LOGGER.info("ViaVersion transport listening on {}", listener.localAddress())
        return listener.localAddress() as InetSocketAddress
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        MinecraftServer.getGlobalEventHandler().removeChild(events)
        quiesce()
        workers.shutdownGracefully().awaitUninterruptibly()
        acceptor.shutdownGracefully().awaitUninterruptibly()
        platform?.close()
        if (initialized && Via.isLoaded()) (Via.getManager() as ViaManagerImpl).destroy()
    }

    fun quiesce() {
        // Include initialization already in progress and reject worker registrations that were
        // queued just before the listener closed, before they can start another reader.
        synchronized(lifecycleLock) { quiescing = true }
        stopAccepting()
        channels.close().awaitUninterruptibly()
        // Reader threads may be waiting for login or a configuration acknowledgement.
        // Disconnect interrupts those waits before we retire the module's event loops.
        val readers = players.toList()
        readers.forEach(ViaPlayerConnection::disconnect)
        readers.forEach(ViaPlayerConnection::awaitReader)
        players.removeAll(readers.toSet())
    }

    override fun stopAccepting() {
        listener?.close()?.awaitUninterruptibly()
    }

    // Expose the actual socket to Minestom's socket-player API without reflection.
    class TransportChannel internal constructor(
        parent: Channel,
        channel: SocketChannel,
    ) : NioSocketChannel(parent, channel) {
        internal fun socketChannel(): SocketChannel = javaChannel()
    }

    class AcceptChannel : NioServerSocketChannel() {
        override fun doReadMessages(messages: MutableList<Any>): Int {
            val socket = javaChannel().accept() ?: return 0
            try {
                messages.add(TransportChannel(this, socket))
                return 1
            } catch (error: Throwable) {
                socket.close()
                throw error
            }
        }
    }
}
