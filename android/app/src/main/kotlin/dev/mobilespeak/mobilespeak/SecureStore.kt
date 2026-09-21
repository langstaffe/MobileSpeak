package dev.mobilespeak.mobilespeak

import android.content.Context
import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SecureStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("mobilespeak.secure", Context.MODE_PRIVATE)
    private var blocked = false
    private var data = JSONObject()

    init {
        val saved = preferences.getString("data", null)
        if (saved != null) {
            try {
                data = JSONObject(String(decrypt(Base64.decode(saved, Base64.NO_WRAP)), Charsets.UTF_8))
            } catch (_: Exception) {
                blocked = true
            }
        }
    }

    val readError: String?
        get() = if (blocked) context.localized(R.string.error_secure_store_read) else null

    @Synchronized
    fun bookmarks(): List<Bookmark> {
        check(!blocked) { context.localized(R.string.error_secure_data_read) }
        return data.optJSONArray("bookmarks").objects().map(Bookmark::from)
    }

    @Synchronized
    fun identity(): JSONObject? {
        check(!blocked) { context.localized(R.string.error_secure_data_read) }
        return data.optJSONObject("identity")
    }

    @Synchronized
    fun saveBookmarks(bookmarks: List<Bookmark>) =
        save(JSONObject(data.toString()).put("bookmarks", JSONArray().apply { bookmarks.forEach { put(it.json()) } }))

    @Synchronized
    fun saveIdentity(identity: JSONObject) =
        save(JSONObject(data.toString()).put("identity", identity))

    @SuppressLint("UseKtx") // The platform commit() result is required to avoid reporting a failed write as saved.
    private fun save(next: JSONObject) {
        check(!blocked) { context.localized(R.string.error_secure_data_overwrite) }
        val encoded = Base64.encodeToString(encrypt(next.toString().toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString("data", encoded).commit()) { context.localized(R.string.error_secure_store_write) }
        data = next
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(value.copyOfRange(IV_SIZE, value.size))
    }

    private companion object {
        const val KEY_ALIAS = "dev.mobilespeak.mobilespeak.secrets"
        const val IV_SIZE = 12
    }
}
