package com.gymcoach.app.data.secure

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gymcoach.app.data.prefs.secretsStore
import com.gymcoach.core.repo.ApiKeyStore
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The Anthropic API key and the home-bridge secret, AES-256/GCM-encrypted with a non-exportable
 * Android-Keystore key ([KeystoreCipher], R2 §6.1) and stored as Base64 iv/ciphertext in the
 * `secrets` DataStore (excluded from backup). The plaintext never reaches a log, a file or an error
 * message; plaintext byte arrays are zeroed after use. Any decrypt failure = "no key stored": the
 * blob is cleared and the user is asked again (§7.4).
 */
class KeystoreApiKeyStore(
    private val appContext: Context,
    private val cipher: KeystoreCipher = KeystoreCipher(),
) : ApiKeyStore {

    override suspend fun hasKey(): Boolean = read() != null

    /** Only Console API keys are accepted (R1 §1.4): anything not starting with `sk-ant-api` is rejected with `backend_key_rejected`. */
    override suspend fun store(key: String) {
        val trimmed = key.trim()
        if (!trimmed.startsWith(API_KEY_PREFIX)) throw IllegalArgumentException(REJECTED)
        writeSecret(KEY_API_IV, KEY_API_CT, trimmed)
    }

    override suspend fun read(): String? = readSecret(KEY_API_IV, KEY_API_CT)

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            appContext.secretsStore.edit { prefs ->
                prefs.remove(KEY_API_IV)
                prefs.remove(KEY_API_CT)
            }
        }
    }

    override suspend fun storeBridgeSecret(secret: String) {
        val trimmed = secret.trim()
        require(trimmed.isNotEmpty()) { "bridge secret is empty" }
        writeSecret(KEY_BRIDGE_IV, KEY_BRIDGE_CT, trimmed)
    }

    override suspend fun readBridgeSecret(): String? = readSecret(KEY_BRIDGE_IV, KEY_BRIDGE_CT)

    // ---- internals -----------------------------------------------------------------------------------

    private suspend fun writeSecret(ivKey: Preferences.Key<String>, ctKey: Preferences.Key<String>, value: String) {
        withContext(Dispatchers.IO) {
            val plain = value.toByteArray(Charsets.UTF_8)
            try {
                val (iv, ct) = cipher.encrypt(plain)
                val ivB64 = Base64.getEncoder().encodeToString(iv)
                val ctB64 = Base64.getEncoder().encodeToString(ct)
                appContext.secretsStore.edit { prefs ->
                    prefs[ivKey] = ivB64
                    prefs[ctKey] = ctB64
                }
            } finally {
                plain.fill(0)
            }
        }
    }

    private suspend fun readSecret(ivKey: Preferences.Key<String>, ctKey: Preferences.Key<String>): String? = withContext(Dispatchers.IO) {
        val prefs = appContext.secretsStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        val ivB64 = prefs[ivKey]
        val ctB64 = prefs[ctKey]
        if (ivB64 == null || ctB64 == null) return@withContext null
        val plain: ByteArray? = try {
            cipher.decrypt(Base64.getDecoder().decode(ivB64), Base64.getDecoder().decode(ctB64))
        } catch (e: IllegalArgumentException) {   // malformed Base64
            null
        }
        if (plain == null) {
            // Failure policy (R2 §6.1): the blob is unusable on this device — clear it, report "no key".
            appContext.secretsStore.edit { p ->
                p.remove(ivKey)
                p.remove(ctKey)
            }
            return@withContext null
        }
        try {
            String(plain, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        const val API_KEY_PREFIX: String = "sk-ant-api"
        /** Resource name of the user-facing copy (strings_backend_policy.xml, WP-08); never the key itself. */
        const val REJECTED: String = "backend_key_rejected"
        val KEY_API_IV: Preferences.Key<String> = stringPreferencesKey("api_key_iv")
        val KEY_API_CT: Preferences.Key<String> = stringPreferencesKey("api_key_ct")
        val KEY_BRIDGE_IV: Preferences.Key<String> = stringPreferencesKey("bridge_secret_iv")
        val KEY_BRIDGE_CT: Preferences.Key<String> = stringPreferencesKey("bridge_secret_ct")
    }
}
