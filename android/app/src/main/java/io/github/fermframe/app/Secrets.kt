package io.github.fermframe.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * FermFrame settings passwords, kept on this phone only and encrypted with a key that lives in Android's
 * own key store (it never leaves the phone, and other apps can't read it). Like the Windows app,
 * which encrypts them for the Windows account.
 */
object Secrets {
    private const val ALIAS = "fermframe-passwords"
    private const val FILE = "fermframe-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    fun get(ctx: Context, mac: String): String? {
        val stored = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(mac, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) { null }
    }

    fun set(ctx: Context, mac: String, password: String?) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (password.isNullOrEmpty()) { prefs.remove(mac).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        prefs.putString(mac, Base64.encodeToString(c.iv + c.doFinal(password.toByteArray()), Base64.NO_WRAP)).apply()
    }
}
