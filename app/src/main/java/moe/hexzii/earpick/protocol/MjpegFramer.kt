package moe.hexzii.earpick.protocol

/**
 * Reassembles the camera's continuous MJPEG byte stream.
 *
 * The firmware slices one continuous stream into UDP packets; the `0x32` field is
 * *not* a fragment count. Frame boundaries are found by scanning for JPEG SOI/EOI
 * markers, and a gap in the 16-bit packet sequence discards the partial frame.
 */
class MjpegFramer(
    private val maxBufferedBytes: Int = 4 * 1024 * 1024,
) {
    private var buffer = ByteArray(64 * 1024)
    private var size = 0
    private var lastSequence = -1

    fun reset() {
        size = 0
        lastSequence = -1
    }

    fun feed(source: ByteArray, offset: Int, length: Int, sequence: Int): List<ByteArray> {
        if (length <= 0 || offset < 0 || offset + length > source.size) return emptyList()

        if (lastSequence >= 0 && sequence != ((lastSequence + 1) and 0xFFFF)) {
            size = 0
        }
        lastSequence = sequence

        append(source, offset, length)

        val frames = ArrayList<ByteArray>(1)
        while (true) {
            val start = indexOf(SOI_0, SOI_1, 0, size)
            if (start < 0) {
                // Keep a trailing 0xFF: the next packet may begin with 0xD8.
                if (size > 1) {
                    buffer[0] = buffer[size - 1]
                    size = 1
                }
                break
            }
            if (start > 0) removePrefix(start)

            val end = indexOf(EOI_0, EOI_1, 0, size)
            if (end < 0) break

            frames += buffer.copyOfRange(0, end + 2)
            removePrefix(end + 2)
        }

        if (size > maxBufferedBytes) size = 0
        return frames
    }

    private fun append(source: ByteArray, offset: Int, length: Int) {
        ensureCapacity(size + length)
        System.arraycopy(source, offset, buffer, size, length)
        size += length
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) {
            capacity = minOf(maxBufferedBytes, capacity * 2)
            if (capacity == maxBufferedBytes) break
        }
        if (capacity < required) {
            // Defensive: drop accumulated garbage rather than grow past the cap.
            size = 0
        }
        buffer = buffer.copyOf(capacity)
    }

    private fun removePrefix(count: Int) {
        if (count >= size) {
            size = 0
            return
        }
        System.arraycopy(buffer, count, buffer, 0, size - count)
        size -= count
    }

    private fun indexOf(first: Byte, second: Byte, from: Int, to: Int): Int {
        var index = from
        while (index + 1 < to) {
            if (buffer[index] == first && buffer[index + 1] == second) return index
            index++
        }
        return -1
    }

    private companion object {
        val SOI_0: Byte = 0xFF.toByte()
        val SOI_1: Byte = 0xD8.toByte()
        val EOI_0: Byte = 0xFF.toByte()
        val EOI_1: Byte = 0xD9.toByte()
    }
}
