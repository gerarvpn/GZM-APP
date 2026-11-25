package com.gzmnet.app.gzm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado local de GZMNET. La clave AES-256 vive en el Android Keystore (no sale del dispositivo)
 * y protege el token, la configuración descargada y la clave del almacén MMKV.
 */
object GzmCrypto {
    private const val ALIAS = "gzmnet_wrap_v1"
    private const val TAG = "GzmCrypto"
    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    private fun ctx(): Context = requireNotNull(appCtx) { "GzmCrypto no inicializado" }

    private inline fun <T> withFileLock(block: () -> T): T {
        val lockFile = File(ctx().filesDir, "gzm.lock")
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.lock().use { return block() }
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return withFileLock {
            val ks2 = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks2.getKey(ALIAS, null) as? SecretKey) ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                gen.generateKey()
            }
        }
    }

    /** Devuelve iv(12) + ciphertext(+tag). */
    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 28) { "blob corrupto" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Escritura atómica de un archivo cifrado en el almacenamiento privado de la app. */
    fun writeSecure(name: String, plain: ByteArray) {
        val dir = ctx().filesDir
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(encrypt(plain))
        val dst = File(dir, name)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            check(tmp.renameTo(dst)) { "no se pudo guardar $name" }
        }
    }

    fun readSecure(name: String): ByteArray? {
        val f = File(ctx().filesDir, name)
        if (!f.exists()) return null
        return try {
            decrypt(f.readBytes())
        } catch (e: Exception) {
            Log.e(TAG, "lectura fallida de $name", e)
            null
        }
    }

    fun deleteSecure(name: String) {
        File(ctx().filesDir, name).delete()
    }

    /** Clave de 16 caracteres para cifrar los archivos MMKV (perfiles, ajustes). */
    fun mmkvKey(): String? = try {
        withFileLock {
            val existing = readSecure("gzm_mk.bin")
            if (existing != null) {
                String(existing, Charsets.US_ASCII)
            } else {
                val raw = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val k = Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                writeSecure("gzm_mk.bin", k.toByteArray(Charsets.US_ASCII))
                k
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "sin clave de Keystore; MMKV sin cifrar", e)
        null
    }
}
