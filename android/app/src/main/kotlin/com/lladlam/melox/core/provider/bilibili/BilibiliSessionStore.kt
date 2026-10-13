package com.lladlam.melox.core.provider.bilibili

import android.content.Context
import android.webkit.CookieManager
import com.lladlam.melox.core.network.MeloXHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class BilibiliSession(
    val cookie: String,
    val sessData: String,
    val biliJct: String,
    val userId: String,
    val buvid3: String,
    val buvid4: String,
) {
    val isLoggedIn: Boolean get() = sessData.isNotBlank() && biliJct.isNotBlank() && userId.isNotBlank()
}

object BilibiliSessionStore {
    private const val PreferencesName = "ysyy_bilibili_session"
    private const val KeyCookie = "cookie"
    private const val KeyRevision = "revision"
    private const val KeyAnonymous = "anonymous_cookie"
    private const val KeyAnonymousAt = "anonymous_at"
    private val RequiredNames = setOf("SESSDATA", "bili_jct", "DedeUserID")

    /**
     * 匿名指纹端点（蓝本：Neri 的 `BILI_FINGERPRINT_URL`）。无需登录即返回一组
     * 设备指纹 cookie，带上它 B 站的搜索类接口就能用。
     */
    private const val FingerprintUrl = "https://api.bilibili.com/x/frontend/finger/spi"
    private const val FingerprintUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
    private const val AnonymousTtlMs = 60L * 60L * 1000L

    fun read(context: Context): BilibiliSession {
        val stored = parse(
            context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
                .getString(KeyCookie, "").orEmpty(),
        )
        // 已登录就直接用登录态，不掺匿名指纹（否则会把请求降级成游客）。
        if (stored.isLoggedIn) return stored
        return stored.copy(cookie = anonymousCookie(context).ifBlank { stored.cookie })
    }

    /**
     * 未登录时的匿名指纹 cookie（**只在缓存里有值时才同步返回**）。
     *
     * 之前未登录时 B 站接口会回「账号未登录」，导致跨源兜底（B 站是候选主力）整条
     * 链失效。实际上搜索/歌单接口只要带 `buvid3`/`buvid4` 这类设备指纹就能匿名调用，
     * 不需要 SESSDATA。
     *
     * ⚠ 这里**不做网络请求**：[read()] 在缓存键计算、B 站歌单/UP 主等同步路径上都会
     * 被调用，冷启动第一次没有缓存时同步联网会把主线程拖住（ANR 风险），而且会把
     * 整条解析链卡在一个最多 10s 的超时上。真正的抓取放在 [primeAnonymousCookie]，
     * 由后台协程预热；拿不到就退回无 cookie（旧行为），不影响其它源。
     */
    private fun anonymousCookie(context: Context): String =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .getString(KeyAnonymous, "").orEmpty()

    /**
     * 后台预热匿名指纹（有缓存则直接返回）。供播放/搜索链路在 IO 协程里调用一次，
     * 之后 [anonymousCookie] 就能同步命中。
     */
    suspend fun primeAnonymousCookie(context: Context): String {
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val cached = preferences.getString(KeyAnonymous, "").orEmpty()
        val cachedAt = preferences.getLong(KeyAnonymousAt, 0L)
        if (cached.isNotBlank() && System.currentTimeMillis() - cachedAt < AnonymousTtlMs) return cached
        if (cached.isNotBlank()) preferences.edit().remove(KeyAnonymous).remove(KeyAnonymousAt).apply()

        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(FingerprintUrl)
                    .header("User-Agent", FingerprintUserAgent)
                    .get()
                    .build()
                MeloXHttpClient.shared.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use ""
                    val data = JSONObject(response.body?.string().orEmpty()).optJSONObject("data")
                        ?: return@use ""
                    listOf(
                        "buvid3" to (data.optString("b_3").ifBlank { data.optString("buvid3") }),
                        "buvid4" to (data.optString("b_4").ifBlank { data.optString("buvid4") }),
                        "buvid_fp" to data.optString("buvid_fp"),
                        "buvid_fp_plain" to data.optString("buvid_fp_plain"),
                        "b_lsid" to data.optString("b_lsid"),
                    ).filter { (_, value) -> value.isNotBlank() }
                        .joinToString("; ") { (key, value) -> "$key=$value" }
                }
            }.getOrDefault("").orEmpty()
        }

        if (fetched.isNotBlank()) {
            preferences.edit()
                .putString(KeyAnonymous, fetched)
                .putLong(KeyAnonymousAt, System.currentTimeMillis())
                .apply()
        }
        return fetched
    }

    fun write(context: Context, cookie: String): BilibiliSession {
        val session = parse(cookie)
        require(session.isLoggedIn) { "Bilibili 登录态不完整" }
        val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        if (preferences.getString(KeyCookie, "").orEmpty() != session.cookie) {
            preferences.edit().putString(KeyCookie, session.cookie)
                .putLong(KeyRevision, preferences.getLong(KeyRevision, 0L) + 1L).apply()
        }
        return session
    }

    fun parse(cookie: String): BilibiliSession {
        val values = cookie.split(';').map(String::trim).mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) null else entry.substring(0, separator) to entry.substring(separator + 1)
        }.toMap()
        val normalized = values.toSortedMap().entries.joinToString("; ") { (key, value) -> "$key=$value" }
        return BilibiliSession(
            cookie = normalized,
            sessData = values["SESSDATA"].orEmpty(),
            biliJct = values["bili_jct"].orEmpty(),
            userId = values["DedeUserID"].orEmpty(),
            buvid3 = values["buvid3"].orEmpty(),
            buvid4 = values["buvid4"].orEmpty(),
        )
    }

    fun clear(context: Context, clearWebCookies: Boolean) {
        val names = read(context).cookie.split(';').mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
        val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        if (preferences.getString(KeyCookie, "").orEmpty().isNotBlank()) {
            preferences.edit().remove(KeyCookie)
                .putLong(KeyRevision, preferences.getLong(KeyRevision, 0L) + 1L).apply()
        }
        if (clearWebCookies && names.isNotEmpty()) runCatching {
            CookieManager.getInstance().let { manager ->
                listOf("https://www.bilibili.com/", "https://passport.bilibili.com/", "https://bilibili.com/").forEach { domain ->
                    names.forEach { manager.setCookie(domain, "$it=; Max-Age=0; Path=/") }
                }
                manager.flush()
            }
        }
    }

    fun hasRequiredCookies(cookie: String): Boolean {
        val names = cookie.split(';').map { it.substringBefore('=').trim() }.toSet()
        return RequiredNames.all(names::contains)
    }

    fun revision(context: Context): Long = context.applicationContext
        .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).getLong(KeyRevision, 0L)

    fun scope(userId: String, revision: Long): String = "user:${userId.ifBlank { "anonymous" }}:r$revision"
}
