package moe.hexzii.earpick.link

enum class LinkPhase {
    /** Hotspot/socket is up, but the camera has not produced a complete frame yet. */
    WaitingForDevice,
    Streaming,
    Failed,
}

enum class LinkFailure {
    Transport,
    Unknown,
}

data class LinkState(
    val phase: LinkPhase = LinkPhase.WaitingForDevice,
    val deviceName: String? = null,
    val batteryAdc: Int? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val kbps: Int = 0,
    val ledOn: Boolean = false,
    val mirror: Boolean = false,
    val packetsReceived: Long = 0,
    val framesReceived: Long = 0,
    val lastPacketAt: Long = 0,
    val failure: LinkFailure? = null,
)
