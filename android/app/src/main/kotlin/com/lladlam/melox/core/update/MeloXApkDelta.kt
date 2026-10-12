package com.lladlam.melox.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * Incremental update support: rebuild the published APK from the installed one
 * plus a small BSDIFF40 patch, instead of downloading ~80 MB again.
 *
 * The reconstructed file is only handed to the system installer after its
 * SHA-256 matches the hash published next to the patch, and the installer itself
 * still enforces that the signature matches the installed app.
 */
internal object MeloXApkDelta {

    private const val TAG = "MeloXApkDelta"
    private const val UpdateDirectory = "melox_downloads"

    @Volatile
    private var cachedInstalledHash: String? = null

    /** SHA-256 of the installed APK, or `null` when it cannot be read. */
    fun installedApkSha256(context: Context): String? {
        cachedInstalledHash?.let { return it }
        val apk = installedApk(context) ?: return null
        return runCatching { sha256(apk) }
            .onFailure { Log.w(TAG, "cannot hash installed APK", it) }
            .getOrNull()
            ?.also { cachedInstalledHash = it }
    }

    fun installedApk(context: Context): File? = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir?.let(::File)
    }.getOrNull()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Applies [patch] to the installed APK and verifies the result against
     * [expectedApkSha256]. Returns the rebuilt APK, or `null` when anything does
     * not line up (the caller then falls back to the full download).
     */
    fun reconstruct(
        context: Context,
        patch: File,
        expectedApkSha256: String,
        versionName: String,
    ): File? {
        val installed = installedApk(context) ?: return null
        val target = File(File(context.filesDir, UpdateDirectory).apply { mkdirs() }, "MeloX-$versionName.apk")
        target.delete()
        val applied = runCatching { BsPatch.apply(installed, patch, target) }
            .onFailure { Log.w(TAG, "patch application failed", it) }
            .isSuccess
        if (!applied) {
            target.delete()
            return null
        }
        val actual = runCatching { sha256(target) }.getOrNull()
        if (actual != expectedApkSha256) {
            Log.w(TAG, "rebuilt APK hash mismatch: $actual != $expectedApkSha256")
            target.delete()
            return null
        }
        return target
    }

    /** Opens the system installer for a rebuilt APK; requires the package's install permission. */
    fun launchInstaller(context: Context, apk: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.onFailure { Log.w(TAG, "cannot launch installer", it) }.getOrDefault(false)
}
