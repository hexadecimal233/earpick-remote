package moe.hexzii.earpick.link

import moe.hexzii.earpick.TestPackets
import moe.hexzii.earpick.protocol.EarpickProtocol
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(20, unit = TimeUnit.SECONDS)
class UdpIntegrationTest {
    @Test
    fun `reassembles a real JPEG from a fake UDP device and accepts commands`() = runBlocking {
        val jpeg = createJpeg()
        FakeDevice(jpeg).use { device ->
            device.start()

            val transport = AndroidUdpTransport(InetAddress.getLoopbackAddress(), device.port)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = EarpickSession(
                transport = transport,
                scope = scope,
                config = EarpickSession.Config(
                    heartbeatIntervalMs = 100,
                    receiveTimeoutMs = 20,
                    stallTimeoutMs = 2_000,
                    statsIntervalMs = 100,
                ),
            )

            try {
                val subscribed = CompletableDeferred<Unit>()
                val frameDeferred = async {
                    session.frames.onSubscription { subscribed.complete(Unit) }.first()
                }
                subscribed.await()
                session.start()

                val frame = withTimeout(5_000) { frameDeferred.await() }
                assertArrayEquals(jpeg, frame)

                val decoded = requireNotNull(ImageIO.read(ByteArrayInputStream(frame)))
                assertEquals(640, decoded.width)
                assertEquals(480, decoded.height)

                withTimeout(3_000) {
                    session.state.first { it.deviceName == "zdeer earpick-3593" && it.batteryAdc == 73 }
                }

                session.setLed(true)
                device.awaitType(EarpickProtocol.TYPE_LED_ON)
            } finally {
                session.close()
                scope.cancel()
            }

            device.awaitType(EarpickProtocol.TYPE_STOP)
        }
    }

    private fun createJpeg(): ByteArray {
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(18, 92, 84)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(240, 244, 240)
            graphics.fillOval(140, 80, 360, 320)
            graphics.color = Color(40, 40, 40)
            graphics.fillOval(245, 170, 150, 150)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "jpg", output)
            output.toByteArray()
        }
    }

    private class FakeDevice(private val jpeg: ByteArray) : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        private val buffer = ByteArray(4096)

        val port: Int get() = socket.localPort
        val receivedTypes = LinkedBlockingQueue<Int>()

        @Volatile
        private var client: InetSocketAddress? = null

        private var sequence = 0
        private var lastVideoAt = 0L

        private val worker = thread(start = false, isDaemon = true, name = "fake-earpick") {
            runLoop()
        }

        fun start() {
            worker.start()
        }

        fun awaitType(type: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val received = receivedTypes.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (received == type) return
            }
            throw AssertionError("Device never received packet type 0x%02X".format(type))
        }

        private fun runLoop() {
            socket.soTimeout = 20
            while (!socket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    handle(packet)
                } catch (_: SocketTimeoutException) {
                    // Fall through and keep streaming to the known client.
                } catch (error: IOException) {
                    if (socket.isClosed) return
                    throw error
                }

                val destination = client ?: continue
                val now = System.nanoTime()
                if (now - lastVideoAt >= TimeUnit.MILLISECONDS.toNanos(20)) {
                    streamFrame(destination)
                    lastVideoAt = now
                }
            }
        }

        private fun handle(packet: DatagramPacket) {
            val data = buffer.copyOf(packet.length)
            if (data.size < 7 || data[0] != 0x63.toByte() || data[1] != 0x63.toByte()) return

            val destination = packet.socketAddress as InetSocketAddress
            client = destination
            val type = data[2].toInt() and 0xFF
            receivedTypes.put(type)

            if (type != EarpickProtocol.TYPE_VIDEO) {
                send(destination, TestPackets.report("zdeer earpick-3593", type))
            }
        }

        private fun streamFrame(destination: InetSocketAddress) {
            var offset = 0
            var frameIndex = 1
            while (offset < jpeg.size) {
                val length = minOf(1400, jpeg.size - offset)
                send(
                    destination,
                    TestPackets.video(
                        payload = jpeg.copyOfRange(offset, offset + length),
                        sequence = sequence,
                        frameIndex = frameIndex,
                    ),
                )
                sequence = (sequence + 1) and 0xFFFF
                offset += length
                frameIndex++
            }
        }

        private fun send(destination: InetSocketAddress, data: ByteArray) {
            socket.send(DatagramPacket(data, data.size, destination))
        }

        override fun close() {
            socket.close()
        }
    }
}
