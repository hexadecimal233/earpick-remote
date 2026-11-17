package moe.hexzii.earpick.link

import moe.hexzii.earpick.TestPackets
import moe.hexzii.earpick.protocol.EarpickPacket
import moe.hexzii.earpick.protocol.EarpickProtocol
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10, unit = TimeUnit.SECONDS)
class EarpickSessionTest {
    @Test
    fun `sends a heartbeat and streams a reassembled frame`() = runBlocking {
        withSession { transport, session ->
            val subscribed = CompletableDeferred<Unit>()
            val frameDeferred = async {
                session.frames.onSubscription { subscribed.complete(Unit) }.first()
            }
            subscribed.await()
            session.start()

            assertArrayEquals(EarpickPacket.heartbeat(), pollType(transport, EarpickProtocol.TYPE_REPORT))

            transport.push(TestPackets.report("zdeer earpick-3593"))
            withTimeout(2_000) {
                session.state.first { it.deviceName == "zdeer earpick-3593" }
            }

            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03, 0xFF.toByte(), 0xD9.toByte())
            transport.push(TestPackets.video(jpeg.copyOfRange(0, 3), sequence = 1))
            transport.push(TestPackets.video(jpeg.copyOfRange(3, jpeg.size), sequence = 2))

            assertArrayEquals(jpeg, withTimeout(2_000) { frameDeferred.await() })
            withTimeout(2_000) {
                session.state.first { it.phase == LinkPhase.Streaming && it.batteryAdc == 73 }
            }
        }
    }

    @Test
    fun `commands increment the sequence and update optimistic state`() = runBlocking {
        val callerThread = Thread.currentThread().name
        withSession(heartbeatIntervalMs = 60_000) { transport, session ->
            session.start()
            pollType(transport, EarpickProtocol.TYPE_REPORT)

            session.setLed(true)
            val ledOn = pollType(transport, EarpickProtocol.TYPE_LED_ON)
            assertEquals(1, u16(ledOn, 3))
            withTimeout(1_000) { session.state.first { it.ledOn } }

            session.toggleMirror()
            val mirror = pollType(transport, EarpickProtocol.TYPE_MIRROR)
            assertEquals(2, u16(mirror, 3))
            withTimeout(1_000) { session.state.first { it.mirror } }

            session.setLed(false)
            val ledOff = pollType(transport, EarpickProtocol.TYPE_LED_OFF)
            assertEquals(3, u16(ledOff, 3))
            withTimeout(1_000) { session.state.first { !it.ledOn } }

            // Regression: button handlers must never send on the caller/main thread.
            assertTrue(transport.sendThreads.none { it == callerThread })
        }
    }

    @Test
    fun `video stall demotes streaming back to waiting`() = runBlocking {
        withSession(stallTimeoutMs = 100) { transport, session ->
            session.start()
            pollType(transport, EarpickProtocol.TYPE_REPORT)

            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
            transport.push(TestPackets.video(jpeg, sequence = 1))

            withTimeout(2_000) { session.state.first { it.phase == LinkPhase.Streaming } }
            withTimeout(2_000) { session.state.first { it.phase == LinkPhase.WaitingForDevice } }
        }
    }

    @Test
    fun `close sends stop and makes further commands no-ops`() = runBlocking {
        withSession(heartbeatIntervalMs = 60_000) { transport, session ->
            session.start()
            pollType(transport, EarpickProtocol.TYPE_REPORT)

            session.close()
            assertEquals(
                EarpickProtocol.TYPE_STOP,
                pollType(transport, EarpickProtocol.TYPE_STOP)[2].toInt() and 0xFF,
            )
            withTimeout(2_000) {
                while (!transport.closed) delay(10)
            }

            session.setLed(true)
            assertTrue(transport.sent.none { it[2].toInt() and 0xFF == EarpickProtocol.TYPE_LED_ON })
        }
    }

    private suspend fun withSession(
        heartbeatIntervalMs: Long = 60,
        stallTimeoutMs: Long = 2_000,
        block: suspend (FakeTransport, EarpickSession) -> Unit,
    ) {
        val transport = FakeTransport()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val session = EarpickSession(
            transport = transport,
            scope = scope,
            config = EarpickSession.Config(
                heartbeatIntervalMs = heartbeatIntervalMs,
                receiveTimeoutMs = 20,
                stallTimeoutMs = stallTimeoutMs,
                statsIntervalMs = 50,
            ),
        )
        try {
            block(transport, session)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    private suspend fun pollType(transport: FakeTransport, type: Int): ByteArray = withTimeout(3_000) {
        while (true) {
            val packet = transport.sent.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (packet.size >= 7 && packet[2].toInt() and 0xFF == type) {
                return@withTimeout packet
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
