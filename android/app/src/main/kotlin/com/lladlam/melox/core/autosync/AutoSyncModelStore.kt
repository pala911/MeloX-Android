package com.lladlam.melox.core.autosync

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Resolves AutoSync model assets without ever touching the network.
 *
 * A language is "installed" when a folder contains both `manifest.json` and the
 * quantized ONNX file it names. Three locations are honoured, in order:
 *
 *  1. the app's *external* files dir — `adb push` friendly, no root needed,
 *  2. the app's internal files dir,
 *  3. APK assets, copied out once on first use (ONNX Runtime needs a real file).
 *
 * Weights are large (tens of MB) and licensed separately, so nothing is bundled
 * by default: a missing model simply leaves the feature unavailable.
 */
object AutoSyncModelStore {

    private const val TAG = "MeloXAutoSync"
    private const val ROOT = "autosync"

    /**
     * 云端权重清单的版本标签。此前由 [AutoSyncModelRepository] 提供，云端下载入口
     * 已随神经对齐一起停用，故这里内联保留 —— [AutoSyncCoordinator.applyStored]
     * 仍用它校验此前缓存的时间轴是否与本机权重版本匹配。
     */
    private const val RELEASE_TAG = "android-models-v0.1.0-beta.2"

    /** Languages that currently have a usable model, sorted for stable display. */
    fun installedLanguages(context: Context): List<String> {
        val languages = sortedSetOf<String>()
        for (directory in searchRoots(context)) {
            directory.listFiles()?.forEach { child ->
                if (child.isDirectory && File(child, "manifest.json").isFile) {
                    languages.add(child.name)
                }
            }
        }
        runCatching {
            context.assets.list(ROOT)?.forEach { name ->
                if (isAssetDirectory(context, "$ROOT/$name")) languages.add(name)
            }
        }
        return languages.filter { language ->
            if (language == "ja") return@filter false // model downloaded is not G2P-ready
            val directory = languageDirectory(context, language) ?: return@filter false
            runCatching {
                JSONObject(File(directory, "manifest.json").readText())
                    .optString("release_tag") == RELEASE_TAG && manifest(context, language) != null
            }.getOrDefault(false)
        }
    }

    fun manifest(context: Context, language: String): AutoSyncManifest? {
        val directory = languageDirectory(context, language) ?: return null
        val file = File(directory, "manifest.json")
        if (!file.isFile) return null
        return runCatching { AutoSyncManifest.parse(file.readText()) }
            .onFailure { Log.w(TAG, "manifest unreadable for $language", it) }
            .getOrNull()
    }

    /** Includes installed pack metadata and the alignment pipeline revision. */
    fun timelineModelKey(context: Context, language: String): String? {
        val directory = languageDirectory(context, language) ?: return null
        val manifest = File(directory, "manifest.json")
        val path = modelPath(context, language) ?: return null
        val model = File(path)
        val revision = if (language == "zh") "streaming-pcm-v3-cif-buffer-v2" else "streaming-pcm-v3"
        val identity = "$revision|${manifest.readText()}|${model.length()}|${model.lastModified()}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Absolute path of the ONNX file the manifest names, or `null` when the
     * language is not installed. Assets are materialised into the internal files
     * dir because ONNX Runtime cannot open an APK asset.
     */
    fun modelPath(context: Context, language: String): String? {
        val manifest = manifest(context, language) ?: return null
        val directory = languageDirectory(context, language) ?: return null
        val model = File(directory, manifest.modelFileName)
        if (model.isFile && model.length() > 0L) return model.absolutePath
        // Asset-only installs: copy the model next to the manifest copy.
        val assetName = "$ROOT/$language/${manifest.modelFileName}"
        val materialised = File(internalDirectory(context, language), manifest.modelFileName)
        return runCatching {
            materialised.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                materialised.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            materialised.absolutePath
        }.onFailure { Log.w(TAG, "cannot materialise asset model for $language", it) }
            .getOrNull()
    }

    /** Directories a language can live in, most specific first. */
    private fun searchRoots(context: Context): List<File> = buildList {
        context.getExternalFilesDir(null)?.let { add(File(it, ROOT)) }
        add(File(context.filesDir, ROOT))
    }

    private fun internalDirectory(context: Context, language: String): File =
        File(File(context.filesDir, ROOT), language)

    /**
     * Returns the first directory holding a manifest: an installed folder when
     * one exists, otherwise a freshly created internal folder for asset copies.
     */
    private fun languageDirectory(context: Context, language: String): File? {
        searchRoots(context).forEach { root ->
            val candidate = File(root, language)
            if (File(candidate, "manifest.json").isFile) return candidate
        }
        val assetsRoot = "$ROOT/$language"
        if (!isAssetDirectory(context, assetsRoot)) return null
        val target = internalDirectory(context, language)
        target.mkdirs()
        val manifest = File(target, "manifest.json")
        if (!manifest.isFile) {
            runCatching {
                context.assets.open("$assetsRoot/manifest.json").use { input ->
                    manifest.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }.onFailure {
                Log.w(TAG, "cannot copy asset manifest for $language", it)
                return null
            }
        }
        return target
    }

    private fun isAssetDirectory(context: Context, path: String): Boolean = runCatching {
        // Assets have no "is directory" API: listing a file returns null/empty.
        !context.assets.list(path).isNullOrEmpty()
    }.getOrDefault(false)
}
