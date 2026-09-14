package net.vsean.gwm_dvr_download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WifiConnector(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 通过 WifiNetworkSpecifier 连接车机热点。系统会弹窗让用户确认。
     * 返回的 Network 只用于本 App 的 socket，手机其他流量仍走原来的网络。
     */
    suspend fun connect(ssid: String, password: String, timeoutMs: Long = 90_000): Network =
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val spec = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }
                    .build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(spec)
                    .build()

                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) {
                            cont.resumeWithException(IOException("未能连接热点（已取消、找不到热点或密码错误）"))
                        }
                    }
                }
                release()
                callback = cb
                cm.requestNetwork(request, cb)
                cont.invokeOnCancellation { release() }
            }
        }

    /** 断开由本 App 发起的热点连接。 */
    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
