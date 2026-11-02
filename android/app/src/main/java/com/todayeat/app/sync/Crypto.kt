package com.todayeat.app.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密（见 docs/SPEC.md 第 3 节）：
 * PBKDF2-HMAC-SHA256(密码, "todayeat-v1:"+小写用户名, 15 万轮, 64 字节)，
 * 前 32 字节是数据密钥（只在本机），后 32 字节的 hex 发给服务器当登录凭据。
 */
object Crypto {
    private const val ROUNDS = 150_000
    private const val IV_LEN = 12

    class Derived(val key: ByteArray, val auth: String)

    fun normalizeUsername(username: String) = username.trim().lowercase()

    fun derive(username: String, password: String): Derived {
        val salt = "todayeat-v1:${normalizeUsername(username)}".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, ROUNDS, 512)
        val out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Derived(out.copyOfRange(0, 32), out.copyOfRange(32, 64).joinToString("") { "%02x".format(it) })
    }

    /** IV + AES-GCM 密文 */
    fun seal(plain: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    /** 密钥不对或被篡改时抛 AEADBadTagException */
    fun open(sealed: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, IV_LEN))
        return cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
    }

    fun encryptSnapshot(json: String, key: ByteArray): String =
        Base64.encodeToString(seal(gzip(json.toByteArray(Charsets.UTF_8)), key), Base64.NO_WRAP)

    fun decryptSnapshot(blob: String, key: ByteArray): String =
        String(gunzip(open(Base64.decode(blob, Base64.NO_WRAP), key)), Charsets.UTF_8)

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    private fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}

/** 数据密钥用 Android Keystore 里不可导出的密钥包一层再存，不落明文 */
object KeyVault {
    private const val ALIAS = "todayeat_sync_wrap"
    private const val PREFS = "todayeat_sync_key"
    private const val KEY = "wrapped"

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun save(context: Context, key: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val wrapped = cipher.iv + cipher.doFinal(key)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): ByteArray? = runCatching {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, raw, 0, 12))
        cipher.doFinal(raw, 12, raw.size - 12)
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
