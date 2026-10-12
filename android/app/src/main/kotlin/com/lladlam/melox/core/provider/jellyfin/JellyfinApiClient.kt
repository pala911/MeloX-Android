package com.lladlam.melox.core.provider.jellyfin

import android.annotation.SuppressLint
import android.security.NetworkSecurityPolicy
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Small synchronous transport; provider methods call it from Dispatchers.IO. */
class JellyfinApiClient(
    httpClient: OkHttpClient,
    private val clientName: String = "MeloX",
    private val clientVersion: String = "0.5.1",
    trustUserServer: Boolean = false,
) {
    private val httpClient = if (trustUserServer) httpClient.trustingUserServer() else httpClient
    fun publicServerInfo(serverUrl: String): JellyfinServerInfo {
        val json = request(serverUrl, "/System/Info/Public", null)
        return JellyfinServerInfo(
            id = json.optString("Id"),
            name = json.optString("ServerName").ifBlank { "Jellyfin" },
            version = json.optString("Version"),
        )
    }

    fun authenticate(serverUrl: String, username: String, password: String): JellyfinSession {
        val normalized = serverUrl.trimEnd('/')
        allowPrivateCleartext(normalized)
        val body = JSONObject().put("Username", username).put("Pw", password)
        val json = request(normalized, "/Users/AuthenticateByName", body)
        val user = json.optJSONObject("User") ?: throw IOException("Jellyfin 登录响应缺少用户信息")
        val token = json.optString("AccessToken").takeIf(String::isNotBlank)
            ?: throw IOException("Jellyfin 登录响应缺少 AccessToken")
        val server = publicServerInfo(normalized)
        return JellyfinSession(
            serverUrl = normalized,
            accessToken = token,
            userId = user.optString("Id"),
            serverId = server.id,
            userName = user.optString("Name").ifBlank { username },
        )
    }

    fun get(session: JellyfinSession, path: String, query: Map<String, String> = emptyMap()): JSONObject =
        request(session.baseUrl, path, null, session, query)

    fun post(session: JellyfinSession, path: String, body: JSONObject = JSONObject()): JSONObject =
        request(session.baseUrl, path, body, session)

    fun delete(session: JellyfinSession, path: String) {
        val request = Request.Builder()
            .url("${session.baseUrl}$path")
            .header("Accept", "application/json")
            .header("X-Emby-Authorization", authorizationHeader())
            .header("X-Emby-Token", session.accessToken)
            .delete()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Jellyfin 请求失败：HTTP ${response.code}")
        }
    }

    private fun request(
        serverUrl: String,
        path: String,
        body: JSONObject?,
        session: JellyfinSession? = null,
        query: Map<String, String> = emptyMap(),
    ): JSONObject {
        val url = "${serverUrl.trimEnd('/')}$path".toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Emby-Authorization", authorizationHeader())
        session?.let { builder.header("X-Emby-Token", it.accessToken) }
        body?.let { builder.post(it.toString().toRequestBody(JSON)) }
        httpClient.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("Jellyfin 请求失败：HTTP ${response.code}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    @SuppressLint("CustomX509TrustManager")
    private fun OkHttpClient.trustingUserServer(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> isPrivateHost(hostname) }
            .build()
    }

    private fun allowPrivateCleartext(serverUrl: String) {
        val host = serverUrl.toHttpUrlOrNull()?.host ?: return
        if (!serverUrl.trim().startsWith("http://", ignoreCase = true) || !isPrivateHost(host)) return
        PrivateCleartextPolicy.allow(host)
    }

    private fun isPrivateHost(hostname: String): Boolean {
        if (hostname.equals("localhost", ignoreCase = true)) return true
        val address = runCatching { InetAddress.getByName(hostname) }.getOrNull() ?: return false
        return address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
    }

    private fun authorizationHeader(): String =
        "MediaBrowser Client=\"$clientName\", Device=\"Android\", DeviceId=\"melox-android\", Version=\"$clientVersion\""

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Lets one private host the user typed in use HTTP for this connection.
 * Domain XML cannot name an arbitrary LAN address. The platform policy is
 * swapped only while that request runs, then put back.
 */
private object PrivateCleartextPolicy {
    fun allow(host: String) {
        if (NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)) return
        // ⚠ 跨设备崩溃加固（2026-10-08）：下面这段反射走隐藏内部类
        //   `libcore.net.NetworkSecurityPolicy` / `android.security.FrameworkNetworkSecurityPolicy`，
        //   Android 9+（落在 minSdk=26 范围内）已限制非 SDK 反射，任一环节都会抛异常。
        //   包进 runCatching：失败只是「本次不走明文例外」——该 LAN 连接可能连不上，
        //   但绝不能把整个请求炸成崩溃。LAN Jellyfin 这类功能是用户主动开启的，
        //   在受限 ROM 上静默降级比闪退更合理。
        runCatching {
            val holder = Class.forName("libcore.net.NetworkSecurityPolicy")
            val instance = holder.getDeclaredField("INSTANCE").apply { isAccessible = true }
            val previous = instance.get(null)
            val open = Class.forName("android.security.FrameworkNetworkSecurityPolicy")
                .getConstructor(Boolean::class.javaPrimitiveType)
                .newInstance(true)
            instance.set(null, open)
            if (!NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)) {
                instance.set(null, previous)
            }
        }
    }
}
