package moe.hexzii.earpick.link

import moe.hexzii.earpick.protocol.EarpickPacket
import moe.hexzii.earpick.protocol.EarpickProtocol
import moe.hexzii.earpick.protocol.MjpegFramer
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One live camera session: 1 Hz heartbeat, receive loop, JPEG reassembly and
 * fire-and-forget control commands.
 *
 * All socket traffic runs on [Dispatchers.IO]: the receive loop sends heartbeats, and a
 * dedicated command actor drains UI commands so button handlers never touch a socket.
 * Sends are serialized so a heartbeat and a command never interleave inside one datagram.
 */
class EarpickSession(
    private val transport: DatagramTransport,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    data class Config(
        val heartbeatIntervalMs: Long = 1_000,
        val receiveTimeoutMs: Int = 250,
        val stallTimeoutMs: Long = 3_000,
        val statsIntervalMs: Long = 500,
    )

    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Conflated JPEG stream: slow decoders skip frames instead of back-pressuring UDP. */
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val framer = MjpegFramer()
    private val commandSequence = AtomicInteger(0)
    private val sendLock = Any()
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private var job: Job? = null
    private var commandJob: Job? = null

    @Volatile
    private var closed = false

    fun start() {
        if (closed || job != null) return
        job = scope.launch(Dispatchers.IO) { receiveLoop() }
        commandJob = scope.launch(Dispatchers.IO) { commandLoop() }
    }

    fun setLed(on: Boolean) {
        enqueue(if (on) EarpickProtocol.TYPE_LED_ON else EarpickProtocol.TYPE_LED_OFF) {
            it.copy(ledOn = on)
        }
    }

    /** The firmware only exposes a mirror command; state is tracked optimistically. */
    fun toggleMirror() {
        enqueue(EarpickProtocol.TYPE_MIRROR) { it.copy(mirror = !it.mirror) }
    }

    fun close() {
        if (closed) return
        closed = true
        commands.close()
        commandJob?.cancel()
        job?.cancel()
        // Best-effort stop frame; must not run on the caller's (main) thread.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { sendRaw(EarpickPacket.command(EarpickProtocol.TYPE_STOP, nextSequence())) }
            runCatching { transport.close() }
        }
    }

    private fun enqueue(type: Int, updateState: (LinkState) -> LinkState) {
        if (closed) return
        commands.trySend(Command(type, updateState))
    }

    private suspend fun commandLoop() {
        for (command in commands) {
            if (closed) break
            try {
                sendRaw(EarpickPacket.command(command.type, nextSequence()))
                _state.update(command.update)
            } catch (_: IOException) {
                fail(LinkFailure.Transport)
                break
            }
        }
    }

    private fun nextSequence(): Int = commandSequence.incrementAndGet() and 0xFFFF

    private fun sendRaw(bytes: ByteArray) {
        synchronized(sendLock) { transport.send(bytes) }
    }

    private data class Command(
        val type: Int,
        val update: (LinkState) -> LinkState,
    )

    private fun fail(failure: LinkFailure) {
        if (closed) return
        _state.update { it.copy(phase = LinkPhase.Failed, failure = failure) }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(4096)
        var lastHeartbeatAt = 0L
        var lastVideoAt = 0L
        var lastStatsAt = clock()
        var framesInWindow = 0
        var bytesInWindow = 0

        try {
            while (currentCoroutineContext().isActive) {
                val before = clock()
                if (lastHeartbeatAt == 0L || before - lastHeartbeatAt >= config.heartbeatIntervalMs) {
                    sendRaw(EarpickPacket.heartbeat())
                    lastHeartbeatAt = before
                }

                val bytesRead = try {
                    transport.receive(buffer, config.receiveTimeoutMs)
                } catch (_: SocketTimeoutException) {
                    0
                }

                val now = clock()
                if (bytesRead < 0) break

                if (bytesRead > 0) {
                    bytesInWindow += bytesRead
                    val type = EarpickPacket.typeOf(buffer, bytesRead)
                    val frames = handlePacket(buffer, bytesRead)
                    _state.update {
                        it.copy(
                            phase = if (frames.isEmpty()) it.phase else LinkPhase.Streaming,
                            packetsReceived = it.packetsReceived + 1,
                            videoPacketsReceived = it.videoPacketsReceived +
                                if (type == EarpickProtocol.TYPE_VIDEO) 1 else 0,
                            framesReceived = it.framesReceived + frames.size,
                            bufferedBytes = framer.bufferedBytes,
                            lastPacketAt = now,
                        )
                    }
                    if (frames.isNotEmpty()) {
                        lastVideoAt = now
                        framesInWindow += frames.size
                        frames.forEach(_frames::tryEmit)
                    }
                } else if (_state.value.phase == LinkPhase.Streaming &&
                    lastVideoAt != 0L &&
                    now - lastVideoAt > config.stallTimeoutMs
                ) {
                    // Keep the last frame on screen while heartbeats try to recover the stream.
                    _state.update { it.copy(phase = LinkPhase.WaitingForDevice) }
                }

                val elapsed = now - lastStatsAt
                if (elapsed >= config.statsIntervalMs) {
                    _state.update {
                        it.copy(
                            fps = ((framesInWindow * 1_000L) / elapsed).toInt(),
                            kbps = ((bytesInWindow * 1_000L) / elapsed / 1024L).toInt(),
                        )
                    }
                    framesInWindow = 0
                    bytesInWindow = 0
                    lastStatsAt = now
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            fail(LinkFailure.Transport)
        } catch (_: RuntimeException) {
            fail(LinkFailure.Unknown)
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int): List<ByteArray> {
        if (!EarpickPacket.hasMagic(buffer, length)) return emptyList()

        if (EarpickPacket.typeOf(buffer, length) != EarpickProtocol.TYPE_VIDEO) {
            EarpickPacket.parseDeviceReport(buffer, length)?.let { report ->
                _state.update { it.copy(deviceName = report.name) }
            }
            return emptyList()
        }

        val chunk = EarpickPacket.parseVideo(buffer, length) ?: return emptyList()
        _state.update {
            it.copy(
                batteryPercent = chunk.batteryPercent,
                width = chunk.width,
                height = chunk.height,
            )
        }
        return framer.feed(
            source = buffer,
            offset = chunk.payloadOffset,
            length = chunk.payloadLength,
            sequence = chunk.sequence,
            byteOffset = chunk.byteOffset,
            framePacketIndex = chunk.framePacketIndex,
        )
    }
}
