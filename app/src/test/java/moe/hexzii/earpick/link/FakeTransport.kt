package moe.hexzii.earpick.link

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class FakeTransport : DatagramTransport {
    private val incoming = LinkedBlockingQueue<ByteArray>()

    val sent = LinkedBlockingQueue<ByteArray>()
    val sendThreads = CopyOnWriteArraySet<String>()

    @Volatile
    var closed = false
        private set

    fun push(packet: ByteArray) {
        incoming.put(packet)
    }

    override fun send(payload: ByteArray) {
        sendThreads += Thread.currentThread().name
        sent.put(payload.copyOf())
    }

    override fun receive(buffer: ByteArray, timeoutMillis: Int): Int {
        val packet = incoming.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        if (packet == null) return if (closed) -1 else 0
        val length = minOf(packet.size, buffer.size)
        packet.copyInto(buffer, 0, 0, length)
        return length
    }

    override fun close() {
        closed = true
    }
}
