package com.exzell.simpooassessment.local

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.Key
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.IvParameterSpec

object CryptographyManager {

    private val APP_ID = "BuildConfig.APPLICATION_ID"
    private val ANDROID_KEYSTORE = "AndroidKeyStore"

    private var secureID: String = ""

    fun setSecureID(context: Context) {
        secureID = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    fun getCipher(): Cipher {
        return generateKey(generateKeyAlias(), true)
    }

    fun encryptData(data: String): String {
        return encryptData(data.toByteArray())
    }

    /**
     * Encrypt data
     * @return hex form on the encrypted bytes
     */
    fun encryptData(data: ByteArray): String {
        if(data.isEmpty()) return ""

        val encryptCipher = generateKey(generateKeyAlias(), true)
        val bytes = encryptCipher.doFinal(data)

        val encryptHex = bytesToHex(bytes)
        val ivBaseHex = bytesToHex(encryptCipher.iv)

        return "${encryptHex}$IV_DELIMITER$ivBaseHex"
    }

    fun decryptData(data: String): ByteArray {
        if(data.isEmpty()) return byteArrayOf()

        val split = data.split(IV_DELIMITER)
        val encryptBase64 = hexToBytes(split[0])
        val iv = hexToBytes(split[1])

        val encryptCipher = generateKey(generateKeyAlias(), false, iv)

        return encryptCipher.doFinal(encryptBase64)
    }

    private fun generateKeyAlias(): String {
        require(secureID.isNotEmpty())

        val androidID = hexToBytes(secureID)
        val appID = APP_ID.toByteArray()

        return MessageDigest.getInstance("MD5").let {
            it.update(androidID)
            val digest = it.digest(appID)

            bytesToHex(digest)
        }
    }

    private fun createFreshKey(alias: String): Key {
        val purpose = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        val keySpec = KeyGenParameterSpec.Builder(alias, purpose)
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(false)
            .build()

        keyGen.init(keySpec)
        return keyGen.generateKey()
    }

    private fun generateKey(alias: String, encrypt: Boolean, ivSpec: ByteArray? = null): Cipher {
        val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        val key = if(keyStore.containsAlias(alias)) {
            keyStore.getKey(alias, null);
        }
        else createFreshKey(alias)

        val cipher = Cipher.getInstance("AES/CBC/PKCS7PADDING");

        val ivParameter = ivSpec?.let { IvParameterSpec(it) }

        if (encrypt) {
            cipher.init(Cipher.ENCRYPT_MODE, key, ivParameter)
        }
        else {
            cipher.init(Cipher.DECRYPT_MODE, key, ivParameter)
        }

        return cipher
    }

    private const val IV_DELIMITER = "[++]"

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return byteArrayOf()

        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val high = hex.substring(i * 2, i * 2 + 1).toInt(16)
            val low = hex.substring(i * 2 + 1, i * 2 + 2).toInt(16)

            result[i] = (high shl 4 or low).toByte()
        }

        return result
    }

    private fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return ""

        val sb = StringBuilder()

        for (b in bytes) {
            var hex = Integer.toHexString(b.toInt() and 0xFF)
            if (hex.length == 1) hex = "0$hex"

            sb.append(hex.uppercase(Locale.getDefault()))
        }

        return sb.toString()
    }
}