package com.velastudio.teltv.util

import android.content.Context
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.security.auth.x500.X500Principal

/** Device-only encrypted preference. No backup, build config, logging or cloud storage. */
object SubDlKeyStore {
    private const val ALIAS = "teltv-subdl-key"
    private const val PREFS = "subdl_credentials"
    private const val FIELD = "encrypted_key"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    @Synchronized fun read(context: Context): String {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FIELD, null)
            ?: return ""
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(ALIAS, null) ?: return ""
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key)
            String(doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    @Suppress("DEPRECATION")
    @Synchronized fun save(context: Context, value: String) {
        val key = value.trim()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (key.isBlank()) {
            check(prefs.edit().remove(FIELD).commit())
            return
        }
        require(key.toByteArray(Charsets.UTF_8).size <= 200 && key.none { it.isWhitespace() })
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            val spec = KeyPairGeneratorSpec.Builder(context.applicationContext)
                .setAlias(ALIAS).setKeySize(2048).setSubject(X500Principal("CN=TelTV SubDL"))
                .setSerialNumber(BigInteger.ONE).setStartDate(start.time).setEndDate(end.time).build()
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        val encrypted = Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, store.getCertificate(ALIAS).publicKey)
            doFinal(key.toByteArray(Charsets.UTF_8))
        }
        check(prefs.edit().putString(FIELD, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
}
