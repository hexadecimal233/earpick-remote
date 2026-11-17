package moe.hexzii.earpick.protocol

/**
 * Wire constants for the zdeer / UDU otoscope link, recovered by reversing
 * com.zdeer.earpick 1.1.2 and verified against real hardware.
 */
object EarpickProtocol {
    const val DEFAULT_HOST = "192.168.0.1"
    const val DEFAULT_PORT = 40000

    const val HEADER_SIZE = 7
    const val VIDEO_HEADER_SIZE = 0x36
    const val MAX_VIDEO_PAYLOAD = 1400

    const val TYPE_REPORT = 0x01
    const val TYPE_STOP = 0x02
    const val TYPE_VIDEO = 0x03
    const val TYPE_MIRROR = 0x05
    const val TYPE_LED_ON = 0x0F
    const val TYPE_LED_OFF = 0x10
}

/** A parsed type-0x03 video packet. [payloadLength] is clamped to the bytes actually present. */
data class VideoChunk(
    val sequence: Int,
    val width: Int,
    val height: Int,
    val batteryPercent: Int,
    /** End offset of this packet's payload inside the current frame (0x0C). */
    val byteOffset: Int,
    /** 1-based packet index inside the current frame (0x30). */
    val framePacketIndex: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

/** A text report the camera sends in reply to heartbeats and LED commands. */
data class DeviceReport(
    val type: Int,
    val name: String,
)

object EarpickPacket {
    fun heartbeat(): ByteArray = command(EarpickProtocol.TYPE_REPORT, 0)

    /** `63 63 <type> <seq-le> 00 00`; commands carry a client-side 16-bit counter. */
    fun command(type: Int, sequence: Int): ByteArray {
        val seq = sequence and 0xFFFF
        return byteArrayOf(
            0x63,
            0x63,
            type.toByte(),
            (seq and 0xFF).toByte(),
            ((seq ushr 8) and 0xFF).toByte(),
            0x00,
            0x00,
        )
    }

    fun hasMagic(packet: ByteArray, length: Int = packet.size): Boolean =
        length >= 2 && packet[0] == 0x63.toByte() && packet[1] == 0x63.toByte()

    fun typeOf(packet: ByteArray, length: Int = packet.size): Int? =
        if (hasMagic(packet, length) && length >= 3) packet[2].toInt() and 0xFF else null

    /**
     * The camera answers with a 7-byte header plus up to 99 NUL-padded UTF-8 bytes
     * containing its SSID, e.g. `zdeer earpick-3593`.
     */
    fun parseDeviceReport(packet: ByteArray, length: Int = packet.size): DeviceReport? {
        if (!hasMagic(packet, length) || length <= EarpickProtocol.HEADER_SIZE) return null
        val type = typeOf(packet, length) ?: return null
        if (type == EarpickProtocol.TYPE_VIDEO) return null

        val textLength = minOf(length - EarpickProtocol.HEADER_SIZE, 99)
        var end = EarpickProtocol.HEADER_SIZE
        val limit = EarpickProtocol.HEADER_SIZE + textLength
        while (end < limit && packet[end] != 0.toByte()) end++
        val name = String(packet, EarpickProtocol.HEADER_SIZE, end - EarpickProtocol.HEADER_SIZE, Charsets.UTF_8)
            .trim()
        return if (name.isEmpty()) null else DeviceReport(type, name)
    }

    fun parseVideo(packet: ByteArray, length: Int = packet.size): VideoChunk? {
        if (!hasMagic(packet, length) || length < EarpickProtocol.VIDEO_HEADER_SIZE) return null
        if (typeOf(packet, length) != EarpickProtocol.TYPE_VIDEO) return null

        val declaredPayload = u16(packet, 0x34)
        val available = length - EarpickProtocol.VIDEO_HEADER_SIZE
        val payloadLength = minOf(declaredPayload, available)
        if (payloadLength <= 0) return null

        return VideoChunk(
            sequence = u16(packet, 0x03),
            width = u16(packet, 0x1C),
            height = u16(packet, 0x1E),
            batteryPercent = u32(packet, 0x10).coerceIn(0, 100),
            byteOffset = u32(packet, 0x0C),
            framePacketIndex = u16(packet, 0x30),
            payloadOffset = EarpickProtocol.VIDEO_HEADER_SIZE,
            payloadLength = payloadLength,
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
