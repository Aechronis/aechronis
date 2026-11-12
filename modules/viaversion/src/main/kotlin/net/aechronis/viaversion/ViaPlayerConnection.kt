package net.aechronis.viaversion

import ac.grim.grimac.minestom.PacketTransport
import com.viaversion.viaversion.api.connection.UserConnection
import com.viaversion.viaversion.api.type.Types
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.util.AttributeKey
import io.netty.util.concurrent.ScheduledFuture
import net.aechronis.server.network.BufferedPacketEventConnection
import net.minestom.server.MinecraftServer
import net.minestom.server.ServerFlag
import net.minestom.server.adventure.MinestomAdventure
import net.minestom.server.entity.GameMode
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.extras.mojangAuth.MojangCrypt
import net.minestom.server.network.ConnectionState
import net.minestom.server.network.NetworkBuffer
import net.minestom.server.network.packet.PacketReading
import net.minestom.server.network.packet.PacketVanilla
import net.minestom.server.network.packet.PacketWriting
import net.minestom.server.network.packet.client.common.ClientCookieResponsePacket
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket
import net.minestom.server.network.packet.client.common.ClientPingRequestPacket
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket
import net.minestom.server.network.packet.client.configuration.ClientSelectKnownPacksPacket
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket
import net.minestom.server.network.packet.client.login.ClientEncryptionResponsePacket
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket
import net.minestom.server.network.packet.client.login.ClientLoginPluginResponsePacket
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket
import net.minestom.server.network.packet.client.play.ClientConfigurationAckPacket
import net.minestom.server.network.packet.client.play.ClientCreativeInventoryActionPacket
import net.minestom.server.network.packet.client.status.StatusRequestPacket
import net.minestom.server.network.packet.server.BufferedPacket
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.login.SetCompressionPacket
import net.minestom.server.network.player.PlayerSocketConnection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

/** Retains Minestom's authenticated socket-player identity, replacing only its byte transport. */
class ViaPlayerConnection internal constructor(
    private val transport: ViaServer.TransportChannel,
    private val compression: MinecraftWireCodec.Compression,
    private val readerFinished: (ViaPlayerConnection) -> Unit,
) : PlayerSocketConnection(transport.socketChannel(), transport.remoteAddress(), Thread.currentThread(), Thread.currentThread()),
    PacketTransport,
    BufferedPacketEventConnection {
    private data class Received(
        val bytes: ByteArray,
        val translated: Boolean,
    )

    private val received = ArrayBlockingQueue<Received>(256)
    private val closed = AtomicBoolean()
    private val queuedBytes = AtomicLong()
    private val reader = Thread.ofVirtual().name("Via-Minecraft-Reader").unstarted(::readPackets)

    @Volatile private var readingState = ConnectionState.HANDSHAKE

    @Volatile private var configurationAcknowledgement: CompletableFuture<Void>? = null
    private var encrypted = false
    private var compressed = false
    private var translationOutput: MutableList<ByteArray>? = null

    // Play packets wait here for the end of the tick that sent them. Waking the event loop and
    // writing to the socket for each packet costs far more than the packet itself.
    private val outgoing = ConcurrentLinkedQueue<SendablePacket>()
    private val drainRequested = AtomicBoolean()
    private val drainAndFlush =
        Runnable {
            drainPackets()
            flushNow()
        }
    private var draining = false

    // Bounds how long packets written from the event loop itself wait when no tick ends.
    private var flushDeadline: ScheduledFuture<*>? = null
    private val minestomContext by lazy { transport.pipeline().context("minestom") }
    private val grimServerContext by lazy { transport.pipeline().context("grim-server") }
    var packetListener: PacketTransport.Listener? = null
        private set

    lateinit var viaConnection: UserConnection
        internal set

    override fun installPacketListener(listener: PacketTransport.Listener?) {
        runOnEventLoop { packetListener = listener }
    }

    override fun runOnEventLoop(action: Runnable) {
        if (transport.eventLoop().inEventLoop()) action.run() else transport.eventLoop().submit(action).syncUninterruptibly()
    }

    override fun sendRaw(
        packet: ByteBuf,
        silent: Boolean,
    ) {
        try {
            // Preserve ordinary Minestom send events and synchronous Grim transaction updates.
            runOnEventLoop {
                val id = Types.VAR_INT.readPrimitive(packet)
                val bytes = ByteArray(packet.readableBytes())
                packet.readBytes(bytes)
                val nativePacket =
                    PacketVanilla.SERVER_PACKET_PARSER.parse(
                        serverState,
                        id,
                        NetworkBuffer.wrap(bytes, 0, bytes.size, MinecraftServer.getRegistries()),
                    )
                writePacket(nativePacket, silent)
                flushSoon()
            }
        } finally {
            packet.release()
        }
    }

    override fun receiveRaw(
        packet: ByteBuf,
        silent: Boolean,
    ) {
        try {
            // PacketEvents injects server-protocol packets, after Via's decoder.
            runOnEventLoop {
                if (!silent) packetListener?.receive(packet, false)
                if (packet.isReadable) receiveTranslated(packet)
            }
        } finally {
            packet.release()
        }
    }

    override fun getProtocolVersion(): Int = viaConnection.protocolInfo.protocolVersion().version

    init {
        transport.attr(KEY).set(this)
    }

    fun startReader() {
        reader.start()
    }

    internal fun awaitReader() {
        reader.join(15000)
        check(!reader.isAlive) { "ViaVersion packet reader did not stop" }
    }

    override fun readThread(): Thread = reader

    override fun setClientState(state: ConnectionState) {
        super.setClientState(state)
        if (state == ConnectionState.CONFIGURATION) configurationAcknowledgement?.complete(null)
    }

    fun incomingLimit(): Int = PacketReading.maxPacketSize(readingState)

    fun receive(packet: ByteBuf) {
        queue(packet, false)
    }

    fun receiveTranslated(packet: ByteBuf) {
        require(packet.readableBytes() <= incomingLimit()) { "Translated packet too large" }
        val output = translationOutput
        if (output != null) {
            val bytes = ByteArray(packet.readableBytes())
            packet.readBytes(bytes)
            output.add(bytes)
        } else {
            queue(packet, true)
        }
    }

    private fun queue(
        packet: ByteBuf,
        translated: Boolean,
    ) {
        if (closed.get()) return
        require(packet.readableBytes() <= incomingLimit()) { "Translated packet too large" }
        if (queuedBytes.addAndGet(packet.readableBytes().toLong()) > 8 * 1024 * 1024) {
            disconnect()
            return
        }
        val bytes = ByteArray(packet.readableBytes())
        packet.readBytes(bytes)
        if (!received.offer(Received(bytes, translated))) disconnect()
    }

    private fun readPackets() {
        try {
            while (!closed.get()) {
                val receivedPacket = received.take()
                queuedBytes.addAndGet(-receivedPacket.bytes.size.toLong())
                val packets =
                    if (receivedPacket.translated) {
                        listOf(receivedPacket.bytes)
                    } else {
                        val translated = ArrayList<ByteArray>()
                        runOnEventLoop {
                            translationOutput = translated
                            try {
                                transport.pipeline().context("frame-queue").fireChannelRead(Unpooled.wrappedBuffer(receivedPacket.bytes))
                            } finally {
                                translationOutput = null
                            }
                        }
                        translated
                    }
                for (bytes in packets) {
                    val buffer = NetworkBuffer.wrap(bytes, 0, bytes.size)
                    buffer.registries(MinecraftServer.getRegistries())
                    val info = PacketVanilla.CLIENT_PACKET_PARSER.stateRegistry(readingState).packetInfo(buffer.read(NetworkBuffer.VAR_INT))
                    if (info.packetClass() == ClientCreativeInventoryActionPacket::class.java &&
                        player?.gameMode != GameMode.CREATIVE
                    ) {
                        continue
                    }
                    val packet = info.serializer().read(buffer)
                    require(buffer.readableBytes() == 0L) { "Trailing bytes in ${packet.javaClass.simpleName}" }
                    readingState = PacketVanilla.nextClientState(packet, readingState)
                    if (packet.javaClass in IMMEDIATE) {
                        MinecraftServer.getPacketListenerManager().processClientPacket(packet, this)
                    } else if (packet is ClientConfigurationAckPacket) {
                        // Wait for the tick to consume earlier gameplay and switch state before
                        // translating another configuration frame, including when Grim is disabled.
                        val acknowledged = CompletableFuture<Void>()
                        configurationAcknowledgement = acknowledged
                        try {
                            checkNotNull(player).addPacketToQueue(packet)
                            acknowledged.get(10, TimeUnit.SECONDS)
                        } finally {
                            configurationAcknowledgement = null
                        }
                    } else {
                        checkNotNull(player) { "Packet received before player initialization" }.addPacketToQueue(packet)
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!closed.get()) MinecraftServer.getExceptionManager().handleException(error)
        } finally {
            try {
                disconnect()
            } finally {
                readerFinished(this)
            }
        }
    }

    private fun onTransport(action: Runnable) {
        if (transport.eventLoop().inEventLoop()) {
            action.run()
        } else {
            try {
                transport.eventLoop().execute(action)
            } catch (error: RejectedExecutionException) {
                // Minestom finishes entity/view cleanup on later ticks, after this generation's
                // event loops have stopped. A disconnected socket has no further output to send.
                if (!closed.get()) throw error
            }
        }
    }

    override fun setEncryptionKey(key: SecretKey) {
        // Login processing waits for installation before it can send encrypted responses.
        runOnEventLoop {
            check(!encrypted) { "Encryption already enabled" }
            // Anything queued before the key was set belongs to the unencrypted stream.
            drainPackets()
            encrypted = true
            transport.pipeline().addFirst(
                "encryption",
                MinecraftWireCodec.Encryption(MojangCrypt.getCipher(1, key), MojangCrypt.getCipher(2, key)),
            )
        }
    }

    override fun startCompression() {
        val threshold = MinecraftServer.getCompressionThreshold()
        check(threshold > 0) { "Compression threshold must be positive" }
        // Queued like any packet, so compression starts exactly where it falls in the stream.
        sendPacket(SetCompressionPacket(threshold))
    }

    override fun sendPacket(packet: SendablePacket) {
        if (closed.get()) return
        outgoing.add(packet)
        requestDrain()
    }

    override fun sendPackets(packets: Collection<SendablePacket>) {
        if (closed.get()) return
        outgoing.addAll(packets)
        requestDrain()
    }

    private fun requestDrain() {
        val eventLoop = transport.eventLoop()
        if (eventLoop.inEventLoop()) {
            drainPackets()
            flushSoon()
            return
        }
        if (!drainRequested.compareAndSet(false, true)) return
        try {
            if (serverState == ConnectionState.PLAY) {
                // The tick's end drains sooner; this is for packets no tick end follows.
                eventLoop.schedule(drainAndFlush, MAX_FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            } else {
                // Status, login and configuration are exchanges of replies with no tick to wait for.
                eventLoop.execute(drainAndFlush)
            }
        } catch (error: RejectedExecutionException) {
            // Minestom finishes entity/view cleanup on later ticks, after this generation's
            // event loops have stopped. A disconnected socket has no further output to send.
            if (!closed.get()) throw error
        }
    }

    /** Sends what this tick queued. Called from the tick thread once the tick's packets are in. */
    internal fun flushTick() {
        if (drainRequested.get()) onTransport(drainAndFlush)
    }

    private fun drainPackets() {
        if (draining) return
        draining = true
        try {
            // Cleared first, so a packet queued from here on requests a drain of its own.
            drainRequested.set(false)
            // Sends initiated by application callbacks follow the current complete batch.
            // Grim's sendRaw remains synchronous for transaction/setback accounting.
            while (true) writePacket(outgoing.poll() ?: break)
        } finally {
            draining = false
        }
    }

    private fun flushSoon() {
        if (serverState != ConnectionState.PLAY) {
            flushNow()
        } else if (flushDeadline == null) {
            flushDeadline = transport.eventLoop().schedule(Runnable(::flushNow), MAX_FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun flushNow() {
        flushDeadline?.cancel(false)
        flushDeadline = null
        transport.flush()
    }

    private fun writePacket(
        sendable: SendablePacket,
        silent: Boolean = false,
    ) {
        if (!transport.isActive) return
        try {
            val state = serverState
            val packet = SendablePacket.extractServerPacket(state, sendable)
            if (packet == null) {
                writeBuffered(sendable as BufferedPacket)
                return
            }
            val published = publish(packet, translate = sendable is ServerPacket.ComponentHolding) ?: return
            write(state, published, encode(state, published), silent)
        } catch (error: Exception) {
            transport.pipeline().fireExceptionCaught(error)
        }
    }

    /** Runs the send event and component translation; null when a listener cancelled the packet. */
    private fun publish(
        packet: ServerPacket,
        translate: Boolean,
    ): ServerPacket? {
        val player = player ?: return packet
        val event = PlayerPacketOutEvent(player, packet)
        EventDispatcher.call(event)
        if (event.isCancelled) return null
        if (translate && ServerFlag.AUTOMATIC_COMPONENT_TRANSLATION && packet is ServerPacket.ComponentHolding) {
            return packet.copyWithOperator { component ->
                MinestomAdventure.COMPONENT_TRANSLATOR.apply(component, player.locale ?: MinestomAdventure.getDefaultLocale())
            }
        }
        return packet
    }

    /** The packet as the pipeline carries it: its id and body, unframed and uncompressed. */
    private fun encode(
        state: ConnectionState,
        packet: ServerPacket,
    ): ByteArray {
        val framed = PacketWriting.allocateTrimmedPacket(state, packet, 0)
        framed.read(NetworkBuffer.VAR_INT)
        return framed.read(NetworkBuffer.RAW_BYTES)
    }

    private fun write(
        state: ConnectionState,
        packet: ServerPacket,
        bytes: ByteArray,
        silent: Boolean,
    ) {
        val next = PacketVanilla.nextServerState(packet, state)
        (if (silent) grimServerContext else minestomContext)
            .write(Unpooled.wrappedBuffer(bytes))
            .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
        if (next != state) serverState = next
        if (packet is SetCompressionPacket) {
            check(!compressed) { "Compression already enabled" }
            compression.enable(packet.threshold)
            compressed = true
        }
    }

    // Minestom's chunk/view broadcasts also use batches of preframed, compressed packets.
    private fun writeBuffered(batch: BufferedPacket) {
        val bytes = ByteArray(Math.toIntExact(batch.length()))
        batch.buffer().copyTo(batch.index(), bytes, 0, bytes.size)
        val frames = Unpooled.wrappedBuffer(bytes)
        val compressedFormat = MinecraftServer.getCompressionThreshold() > 0
        try {
            while (frames.isReadable) {
                val length = Types.VAR_INT.readPrimitive(frames)
                require(length > 0 && length <= frames.readableBytes()) { "Invalid buffered packet" }
                val frame = frames.readSlice(length)
                val payload = if (compressedFormat) MinecraftWireCodec.decompress(frame, ServerFlag.MAX_PACKET_SIZE) else frame.retain()
                val encoded = ByteArray(payload.readableBytes())
                try {
                    payload.readBytes(encoded)
                } finally {
                    payload.release()
                }
                val state = serverState
                val buffer = NetworkBuffer.wrap(encoded, 0, encoded.size, MinecraftServer.getRegistries())
                val packet = PacketVanilla.SERVER_PACKET_PARSER.parse(state, buffer.read(NetworkBuffer.VAR_INT), buffer)
                val published = publish(packet, translate = true) ?: continue
                // The frame already holds this packet's encoding; only a translated copy needs its own.
                write(state, published, if (published === packet) encoded else encode(state, published), silent = false)
            }
        } finally {
            frames.release()
        }
    }

    override fun disconnect() {
        if (!closed.compareAndSet(false, true)) return
        super.disconnect()
        reader.interrupt()
        received.clear()
        // Preserve any queued kick/disconnect packet before closing the socket.
        onTransport {
            drainPackets()
            val first = transport.pipeline().firstContext()
            if (first == null || !transport.isActive) {
                transport.close()
            } else {
                first.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
            }
        }
    }

    companion object {
        val KEY: AttributeKey<ViaPlayerConnection> = AttributeKey.valueOf("aechronis-via-player")

        // Half a tick, the interval at which Minestom's own socket writer wakes.
        private const val MAX_FLUSH_DELAY_MILLIS = 25L

        // Match Minestom's immediate packet dispatch; gameplay remains on the player tick thread.
        private val IMMEDIATE =
            setOf(
                ClientHandshakePacket::class.java,
                ClientCookieResponsePacket::class.java,
                StatusRequestPacket::class.java,
                ClientPingRequestPacket::class.java,
                ClientKeepAlivePacket::class.java,
                ClientLoginStartPacket::class.java,
                ClientEncryptionResponsePacket::class.java,
                ClientLoginPluginResponsePacket::class.java,
                ClientSelectKnownPacksPacket::class.java,
                ClientLoginAcknowledgedPacket::class.java,
                ClientFinishConfigurationPacket::class.java,
            )
    }
}
