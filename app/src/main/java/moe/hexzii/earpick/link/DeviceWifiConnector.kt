package moe.hexzii.earpick.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class WifiUnavailableException : IOException("No matching device hotspot")

/**
 * Requests the camera's open hotspot with the peer-to-peer Wi-Fi API.
 *
 * The connection is app-scoped and does not require a location permission; the
 * system shows a one-time picker/confirmation dialog for SSID patterns.
 */
class DeviceWifiConnector(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _lost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val lost: SharedFlow<Unit> = _lost.asSharedFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun connect(ssidPrefix: String): Network = suspendCancellableCoroutine { continuation ->
        release()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX))
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // App-scoped Wi-Fi networks are often secondary to the primary
                // internet connection; bind the process so the camera's replies are
                // routed back through this network, not the default one.
                runCatching { connectivityManager.bindProcessToNetwork(network) }
                if (continuation.isActive) continuation.resume(network)
            }

            override fun onUnavailable() {
                if (continuation.isActive) {
                    continuation.resumeWithException(WifiUnavailableException())
                }
            }

            override fun onLost(network: Network) {
                runCatching { connectivityManager.bindProcessToNetwork(null) }
                _lost.tryEmit(Unit)
            }
        }

        callback = networkCallback
        try {
            connectivityManager.requestNetwork(request, networkCallback)
        } catch (e: RuntimeException) {
            callback = null
            if (continuation.isActive) continuation.resumeWithException(e)
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation { release() }
    }

    fun localIpv4(network: Network): InetAddress? =
        connectivityManager.getLinkProperties(network)
            ?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.address

    /** Drops the app-scoped network request; the system may disconnect the hotspot. */
    fun release() {
        callback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        callback = null
        runCatching { connectivityManager.bindProcessToNetwork(null) }
    }
}
