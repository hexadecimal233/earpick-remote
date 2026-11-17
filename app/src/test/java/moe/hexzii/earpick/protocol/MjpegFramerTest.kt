package moe.hexzii.earpick.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MjpegFramerTest {
    @Test
    fun `emits a frame split across packets`() {
        val framer = MjpegFramer()
        val first = byteArrayOf(0x00, 0xFF.toByte())
        val second = byteArrayOf(
            0xD8.toByte(), 0x11, 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte(), 0x44,
        )

        assertTrue(framer.feed(first, 0, first.size, sequence = 1).isEmpty())
        val frames = framer.feed(second, 0, second.size, sequence = 2)

        assertEquals(1, frames.size)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte()),
            frames.single(),
        )
    }

    @Test
    fun `drops the partial frame when a packet is missing`() {
        val framer = MjpegFramer()
        assertTrue(
            framer.feed(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02), 0, 4, sequence = 10)
                .isEmpty(),
        )

        // Sequence 11 is lost; packet 12 must not complete the stale frame.
        assertTrue(
            framer.feed(
                byteArrayOf(0x03, 0x04, 0xFF.toByte(), 0xD9.toByte()),
                0,
                4,
                sequence = 12,
            ).isEmpty(),
        )

        val frames = framer.feed(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x09, 0xFF.toByte(), 0xD9.toByte()),
            0,
            5,
            sequence = 13,
        )
        assertEquals(1, frames.size)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x09, 0xFF.toByte(), 0xD9.toByte()),
            frames.single(),
        )
    }

    @Test
    fun `does not reset on 16 bit sequence wrap`() {
        val framer = MjpegFramer()
        assertTrue(
            framer.feed(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01), 0, 3, sequence = 65535)
                .isEmpty(),
        )

        val frames = framer.feed(
            byteArrayOf(0x02, 0xFF.toByte(), 0xD9.toByte()),
            0,
            3,
            sequence = 0,
        )
        assertEquals(1, frames.size)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte()),
            frames.single(),
        )
    }

    @Test
    fun `extracts multiple frames and skips garbage`() {
        val payload = byteArrayOf(
            0x00, 0x01,
            0xFF.toByte(), 0xD8.toByte(), 0x10, 0xFF.toByte(), 0xD9.toByte(),
            0x55,
            0xFF.toByte(), 0xD8.toByte(), 0x20, 0xFF.toByte(), 0xD9.toByte(),
            0x66,
        )

        val frames = MjpegFramer().feed(payload, 0, payload.size, sequence = 7)

        assertEquals(2, frames.size)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x10, 0xFF.toByte(), 0xD9.toByte()),
            frames[0],
        )
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x20, 0xFF.toByte(), 0xD9.toByte()),
            frames[1],
        )
    }

    @Test
    fun `offset continuity survives global sequence jumps`() {
        val framer = MjpegFramer()
        val first = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)
        val second = byteArrayOf(0x02, 0xFF.toByte(), 0xD9.toByte())

        assertTrue(
            framer.feed(first, 0, first.size, sequence = 10, byteOffset = 3, framePacketIndex = 1)
                .isEmpty(),
        )
        val frames = framer.feed(
            second,
            0,
            second.size,
            sequence = 999,
            byteOffset = 6,
            framePacketIndex = 2,
        )

        assertEquals(1, frames.size)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte()),
            frames.single(),
        )
    }

    @Test
    fun `offset gap drops the partial frame`() {
        val framer = MjpegFramer()
        assertTrue(
            framer.feed(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01),
                0,
                3,
                sequence = 1,
                byteOffset = 3,
                framePacketIndex = 1,
            ).isEmpty(),
        )
        // Packet 2 is missing: the next offset jumps from 3 to 8.
        assertTrue(
            framer.feed(
                byteArrayOf(0x02, 0x03),
                0,
                2,
                sequence = 3,
                byteOffset = 8,
                framePacketIndex = 3,
            ).isEmpty(),
        )

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x09, 0xFF.toByte(), 0xD9.toByte())
        val frames = framer.feed(
            jpeg,
            0,
            jpeg.size,
            sequence = 4,
            byteOffset = jpeg.size,
            framePacketIndex = 1,
        )
        assertEquals(1, frames.size)
        assertArrayEquals(jpeg, frames.single())
    }

    @Test
    fun `recovers after garbage without a start marker`() {
        val framer = MjpegFramer()
        val garbage = ByteArray(4096) { 0x11 }
        repeat(8) { index ->
            assertTrue(framer.feed(garbage, 0, garbage.size, sequence = index).isEmpty())
        }

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42, 0xFF.toByte(), 0xD9.toByte())
        val frames = framer.feed(jpeg, 0, jpeg.size, sequence = 100)
        assertEquals(1, frames.size)
        assertArrayEquals(jpeg, frames.single())
    }
}
