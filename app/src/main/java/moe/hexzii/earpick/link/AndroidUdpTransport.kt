package moe.hexzii.earpick.link

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plain UDP transport for the camera endpoint. The camera hotspot is joined manually,
 * so the system's on-link route is enough; no process or socket binding is needed.
 */
class AndroidUdpTransport(
    address: InetAddress,
    port: Int,
) : DatagramTransport {
    private val socket = DatagramSocket(null)
    private val target = InetSocketAddress(address, port)
    private val closed = AtomicBoolean(false)

    init {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
    }

    override fun send(payload: ByteArray) {
        if (closed.get()) throw IOException("Transport closed")
        socket.send(DatagramPacket(payload, payload.size, target))
    }

    override fun receive(buffer: ByteArray, timeoutMillis: Int): Int {
        if (closed.get()) return -1
        socket.soTimeout = timeoutMillis
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            packet.length
        } catch (_: SocketTimeoutException) {
            0
        } catch (e: SocketException) {
            if (closed.get()) -1 else throw e
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.close()
        }
    }
}
