package com.lladlam.melox.core.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * 移动数据音质（设置里的单档位下拉）：
 * - 存 null（跟随当前音质设置，也是默认值）或一档显式音质（标准/高品质/无损/Hi-Res）；
 * - 仅非 WiFi 网络生效，按默认网络的 transport 判定，断网空窗按非 WiFi 计（Neri
 *   resolveTrafficAwareQuality 同口径，判定挪到了 [MusicQualityRuntime.selected] 读取点）；
 * - 不做按源分设、不做纯开关（需求拍板）。
 */
object MobileDataQualityRuntime {
    private const val PREFERENCES_NAME = "ysyy_playback"
    private const val KEY_QUALITY = "mobile_data_quality"

    /** null = 跟随当前音质设置（默认）。 */
    @Volatile
    var overrideQuality: MusicQuality? = null
        private set

    /** true = 当前默认网络不是 WiFi；只有它为 true 时覆盖档参与生效值计算。 */
    @Volatile
    var onMobileData: Boolean = false
        private set

    /** 设置页回显用：读原始偏好，不看网络状态。 */
    fun readPreference(context: Context): MusicQuality? = preferenceOf(context)
        .getString(KEY_QUALITY, null)
        ?.let { MusicQuality.fromApiLevel(it) }

    /** 写设置页选择；null = 跟随当前音质设置（等价于删除该键）。 */
    fun writePreference(context: Context, quality: MusicQuality?) {
        overrideQuality = quality
        preferenceOf(context).edit()
            .putString(KEY_QUALITY, quality?.apiLevel)
            .apply()
    }

    /**
     * 进程内一次（[com.lladlam.melox.MeloXApplication.onCreate] 调用）：补读偏好与
     * 当前网络，再监听默认网络切换。回调落在主线程，两个状态都是 volatile。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        overrideQuality = preferenceOf(app).getString(KEY_QUALITY, null)
            ?.let { MusicQuality.fromApiLevel(it) }
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        onMobileData = manager.activeNetworkCapabilities()?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                onMobileData = !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }

            override fun onLost(network: Network) {
                // 默认网络断开：随后的 onAvailable/onCapabilitiesChanged 会带新网络
                // 能力；空窗期按非 WiFi 计，与"没有 WiFi 就降档"的口径一致。
                onMobileData = true
            }
        })
    }

    private fun preferenceOf(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun ConnectivityManager.activeNetworkCapabilities(): NetworkCapabilities? =
        activeNetwork?.let(::getNetworkCapabilities)
}
