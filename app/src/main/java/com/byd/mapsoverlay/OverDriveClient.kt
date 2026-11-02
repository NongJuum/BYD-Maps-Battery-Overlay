package com.byd.mapsoverlay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Native HTTP client for the OverDrive remote API exposed through the user's zrok URL.
 * Device token is encrypted with an Android Keystore AES-GCM key; JWT is memory-only.
 */
class OverDriveClient(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("overdrive_connection", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    @Volatile private var jwt: String? = null
    @Volatile private var jwtExpiryMs: Long = 0L

    fun configured(): Boolean = prefs.getString(KEY_TOKEN_ENC, null)?.isNotBlank() == true

    fun save(baseUrl: String, deviceToken: String) {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "OverDrive URL must use HTTPS" }
        require(deviceToken.trim().isNotEmpty()) { "Device Token is required" }
        val encrypted = encrypt(deviceToken.trim())
        prefs.edit().putString(KEY_BASE_URL, normalized)
            .putString(KEY_TOKEN_ENC, encrypted).apply()
        jwt = null
        jwtExpiryMs = 0L
    }

    fun savedBaseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_URL) ?: DEFAULT_URL

    /**
     * Returns true only when an authenticated OverDrive snapshot contains a valid SoC.
     */
    fun fetch(onData: (soc: Double, consumption: Double, rangeKm: Int, remainKwh: Double, status: String) -> Unit): Boolean {
        if (!configured()) return false
        return try {
            val base = savedBaseUrl()
            val token = decrypt(prefs.getString(KEY_TOKEN_ENC, null) ?: return false)
            val bearer = getJwt(base, token)
            val response = request("$base/api/mqtt/telemetry", "GET", bearer, null)
            if (response.code !in 200..299) {
                if (response.code == 401 || response.code == 403) {
                    jwt = null
                    jwtExpiryMs = 0L
                }
                onData(-1.0, -1.0, 0, -1.0, "OverDrive HTTP ${response.code}")
                return false
            }
            val root = JSONObject(response.body)
            if (!root.optBoolean("success", false)) {
                onData(-1.0, -1.0, 0, -1.0, root.optString("error", "OverDrive API error"))
                return false
            }
            val data = root.optJSONObject("telemetry") ?: JSONObject()
            val soc = data.optDouble("soc", -1.0)
            val consumption = data.optDouble("consumption_50km", -1.0)
            val range = data.optDouble("ev_range_km", -1.0).toInt()
            val remain = data.optDouble("capacity", -1.0)
            val validSoc = soc.isFinite() && soc in 0.0..100.0
            onData(if (validSoc) soc else -1.0, consumption, range, remain, if (validSoc) "OverDrive connected · live telemetry" else "OverDrive connected · waiting for SoC")
            validSoc
        } catch (e: Exception) {
            Log.w(TAG, "OverDrive request failed: ${e.javaClass.simpleName}")
            onData(-1.0, -1.0, 0, -1.0, "OverDrive unavailable: ${e.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    private fun getJwt(base: String, token: String): String {
        val cached = jwt
        if (!cached.isNullOrBlank() && System.currentTimeMillis() < jwtExpiryMs - 60_000) return cached
        val body = JSONObject().put("token", token).toString()
        val response = request("$base/auth/token", "POST", null, body)
        if (response.code !in 200..299) throw IllegalStateException("auth HTTP ${response.code}")
        val json = JSONObject(response.body)
        if (!json.optBoolean("success", false)) throw SecurityException(json.optString("error", "Invalid device token"))
        val issued = json.optString("jwt", "")
        if (issued.isBlank()) throw SecurityException("JWT missing from OverDrive response")
        jwt = issued
        val ttl = json.optLong("expiresIn", 86400L).coerceAtLeast(300L)
        jwtExpiryMs = System.currentTimeMillis() + ttl * 1000L
        return issued
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun request(url: String, method: String, bearer: String?, body: String?): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > 12) { "Encrypted token is invalid" }
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "OverDriveClient"
        private const val KEY_ALIAS = "byd_maps_overdrive_token"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN_ENC = "device_token_encrypted"
        const val DEFAULT_URL = "https://overdrivezx472a.share.zrok.io"
    }
}
