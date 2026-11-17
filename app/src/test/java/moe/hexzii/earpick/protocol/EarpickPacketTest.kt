package moe.hexzii.earpick.protocol

import moe.hexzii.earpick.TestPackets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EarpickPacketTest {
    @Test
    fun `heartbeat is seven zero bytes behind the magic`() {
        assertArrayEquals(
            byteArrayOf(0x63, 0x63, 0x01, 0x00, 0x00, 0x00, 0x00),
            EarpickPacket.heartbeat(),
        )
    }

    @Test
    fun `commands encode the sequence little endian`() {
        assertArrayEquals(
            byteArrayOf(0x63, 0x63, 0x0F, 0x34, 0x12, 0x00, 0x00),
            EarpickPacket.command(EarpickProtocol.TYPE_LED_ON, 0x1234),
        )
        assertEquals(
            EarpickProtocol.TYPE_LED_OFF,
            EarpickPacket.command(EarpickProtocol.TYPE_LED_OFF, 0xFFFF)[2].toInt() and 0xFF,
        )
    }

    @Test
    fun `video header exposes geometry battery and clamped payload`() {
        val payload = ByteArray(10) { it.toByte() }
        val packet = TestPackets.video(payload, sequence = 9, batteryPercent = 73)

        val chunk = requireNotNull(EarpickPacket.parseVideo(packet))

        assertEquals(9, chunk.sequence)
        assertEquals(640, chunk.width)
        assertEquals(480, chunk.height)
        assertEquals(73, chunk.batteryPercent)
        assertEquals(payload.size, chunk.byteOffset)
        assertEquals(1, chunk.framePacketIndex)
        assertEquals(payload.size, chunk.payloadLength)
        assertEquals(EarpickProtocol.VIDEO_HEADER_SIZE, chunk.payloadOffset)
        assertArrayEquals(payload, packet.copyOfRange(chunk.payloadOffset, chunk.payloadOffset + chunk.payloadLength))
    }

    @Test
    fun `declared payload length cannot exceed received bytes`() {
        val packet = TestPackets.video(ByteArray(5), sequence = 1)
        // Header claims 1400 bytes but only 5 arrived: the parser clamps.
        packet[0x34] = 0x78
        packet[0x35] = 0x05

        val chunk = requireNotNull(EarpickPacket.parseVideo(packet))
        assertEquals(5, chunk.payloadLength)
    }

    @Test
    fun `device report stops at the first NUL`() {
        val packet = TestPackets.report("zdeer earpick-3593")
        val report = requireNotNull(EarpickPacket.parseDeviceReport(packet))
        assertEquals(EarpickProtocol.TYPE_REPORT, report.type)
        assertEquals("zdeer earpick-3593", report.name)
    }

    @Test
    fun `rejects foreign short and video-report packets`() {
        assertNull(EarpickPacket.parseVideo(byteArrayOf(0x00, 0x63, 0x63, 0x03)))
        assertNull(EarpickPacket.parseVideo(TestPackets.video(ByteArray(4), sequence = 1).copyOf(10)))
        assertNull(EarpickPacket.parseDeviceReport(TestPackets.video(ByteArray(4), sequence = 1)))
        assertNull(EarpickPacket.parseDeviceReport(TestPackets.report("")))
    }
}
