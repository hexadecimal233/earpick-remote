package moe.hexzii.earpick.link

/**
 * Minimal datagram seam. Implementations may bind the socket to a specific
 * [android.net.Network] or talk to a local test endpoint.
 */
interface DatagramTransport {
    fun send(payload: ByteArray)

    /**
     * Blocking receive with a timeout.
     *
     * @return number of bytes read, `0` on timeout, or `-1` when the transport is closed.
     */
    fun receive(buffer: ByteArray, timeoutMillis: Int): Int

    fun close()
}
