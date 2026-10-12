package com.lladlam.melox.core.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Opens the app's encrypted session stores with a recovery path.
 *
 * Android Keystore entries are not durable: a platform or keystore reset, or a
 * corrupt ciphertext file, makes [EncryptedSharedPreferences.create] throw. That
 * used to crash the process at startup because the stores are opened while the
 * first Compose screen is built. Each store now degrades instead of crashing:
 *
 * 1. drop the unreadable ciphertext file and rebuild against the existing key;
 * 2. if the master key itself is unusable, remove it and rebuild (every store
 *    sharing that key is already unreadable, so nothing readable is lost);
 * 3. as a last resort keep values in memory for the lifetime of the process.
 *
 * Recovery loses the affected sessions, so the user signs in again.
 */
internal object SecureSessionPreferences {
    private const val Tag = "MeloXSecurePrefs"
    private val instances = mutableMapOf<String, SharedPreferences>()

    @Synchronized
    fun open(context: Context, legacyName: String): SharedPreferences {
        val app = context.applicationContext
        val fileName = "${legacyName}_encrypted_v1"
        instances[fileName]?.let { return it }
        val encrypted = openEncrypted(app, fileName)
        val legacy = app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        // A committed marker prevents stale plaintext from replacing newer credentials
        // if the process exits between the encrypted commit and legacy cleanup.
        if (!encrypted.getBoolean("_migration_complete", false)) {
            val editor = encrypted.edit()
            legacy.all.forEach { (key, value) ->
                if (!encrypted.contains(key)) when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> {
                        require(value.all { it is String })
                        editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                    null -> Unit
                    else -> error("Unsupported session preference type")
                }
            }
            check(editor.putBoolean("_migration_complete", true).commit()) {
                "Unable to persist encrypted session migration"
            }
        }
        check(legacy.edit().clear().commit()) { "Unable to clear migrated session data" }
        instances[fileName] = encrypted
        return encrypted
    }

    /** Opens an encrypted store by its exact file name, with the same recovery path. */
    @Synchronized
    fun openEncrypted(context: Context, fileName: String): SharedPreferences {
        val app = context.applicationContext
        instances[fileName]?.let { return it }
        val preferences = openWithRecovery(app, fileName)
        instances[fileName] = preferences
        return preferences
    }

    private fun openWithRecovery(app: Context, fileName: String): SharedPreferences {
        runCatching { return createVerified(app, fileName) }
            .onFailure { Log.w(Tag, "Encrypted store '$fileName' is unreadable; dropping it", it) }

        runCatching { app.deleteSharedPreferences(fileName) }
        runCatching { return createVerified(app, fileName) }
            .onFailure { Log.w(Tag, "Encrypted store '$fileName' still unreadable; resetting master key", it) }

        runCatching { app.deleteSharedPreferences(fileName) }
        runCatching { deleteMasterKey() }
        runCatching { return createVerified(app, fileName) }
            .onFailure { Log.w(Tag, "Encrypted store '$fileName' unavailable; falling back to memory", it) }

        return InMemorySharedPreferences()
    }

    private fun createVerified(app: Context, fileName: String): SharedPreferences {
        val preferences = EncryptedSharedPreferences.create(
            app,
            fileName,
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        // Force every entry through the cipher so a corrupt value is detected now
        // rather than on the first read from the UI.
        preferences.all
        return preferences
    }

    private fun deleteMasterKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
    }
}

/**
 * Process-local [SharedPreferences] used only when the encrypted store cannot be
 * created at all. Sessions live until the process ends instead of crashing.
 */
private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

    override fun getString(key: String, defValue: String?): String? =
        synchronized(values) { values[key] as? String ?: defValue }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(values) {
            @Suppress("UNCHECKED_CAST")
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        }

    override fun getInt(key: String, defValue: Int): Int =
        synchronized(values) { values[key] as? Int ?: defValue }

    override fun getLong(key: String, defValue: Long): Long =
        synchronized(values) { values[key] as? Long ?: defValue }

    override fun getFloat(key: String, defValue: Float): Float =
        synchronized(values) { values[key] as? Float ?: defValue }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        synchronized(values) { values[key] as? Boolean ?: defValue }

    override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            synchronized(values) {
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }
}
