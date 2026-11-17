package moe.hexzii.earpick

import moe.hexzii.earpick.protocol.EarpickProtocol

/** Builds on-wire packets exactly as the firmware does, for tests. */
object TestPackets {
    fun video(
        payload: ByteArray,
        sequence: Int,
        frameIndex: Int = 1,
        batteryPercent: Int = 73,
        width: Int = 640,
        height: Int = 480,
        byteOffset: Int = -1,
    ): ByteArray {
        val packet = ByteArray(EarpickProtocol.VIDEO_HEADER_SIZE + payload.size)
        packet[0] = 0x63
        packet[1] = 0x63
        packet[2] = EarpickProtocol.TYPE_VIDEO.toByte()
        putU16(packet, 0x03, sequence)
        putU16(packet, 0x05, packet.size)
        packet[0x07] = 0x03
        putU32(packet, 0x0C, if (byteOffset > 0) byteOffset else payload.size)
        putU32(packet, 0x10, batteryPercent)
        putU16(packet, 0x1C, width)
        putU16(packet, 0x1E, height)
        putU16(packet, 0x30, frameIndex)
        putU16(packet, 0x32, frameIndex + 1)
        putU16(packet, 0x34, payload.size)
        payload.copyInto(packet, EarpickProtocol.VIDEO_HEADER_SIZE)
        return packet
    }

    fun report(name: String, type: Int = EarpickProtocol.TYPE_REPORT, seq: Int = 0): ByteArray {
        val packet = ByteArray(EarpickProtocol.HEADER_SIZE + 99)
        packet[0] = 0x63
        packet[1] = 0x63
        packet[2] = type.toByte()
        putU16(packet, 0x03, seq)
        putU16(packet, 0x05, 0x0063)
        val text = name.toByteArray(Charsets.UTF_8)
        text.copyInto(packet, EarpickProtocol.HEADER_SIZE)
        return packet
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
