package de.lulurobb.keypassmobile.platform.clipboard

import de.lulurobb.keypassmobile.platform.keystore.KeySpecs
import java.nio.CharBuffer
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Keyed digest of a copied value, to recognize it in the clipboard later and to persist in the
 * pending-clear marker. A plain hash of a password in a file could be guessed offline; this one
 * cannot be computed without the keystore key.
 */
internal interface ClipboardMac {
    /** HMAC of [text]; null when the key is unavailable (then nothing is persisted). Never throws. */
    fun mac(text: CharSequence): ByteArray?

    /** Plain SHA-256, for tests only: never use it for what is stored. */
    object Plain : ClipboardMac {
        override fun mac(text: CharSequence): ByteArray = ClipboardGuard.digestOf(text)
    }
}

/**
 * [ClipboardMac] with HMAC-SHA256 under a non-exportable AndroidKeyStore key, created on first use
 * ([KeySpecs.CLIPBOARD_KEY_ALIAS]). A key that cannot be used (invalidated, keystore reset) is replaced
 * once; markers made with the old key then simply no longer match.
 */
internal class KeystoreClipboardMac(private val alias: String = KeySpecs.CLIPBOARD_KEY_ALIAS) : ClipboardMac {
    private val lock = Any()
    private var key: SecretKey? = null

    override fun mac(text: CharSequence): ByteArray? = synchronized(lock) {
        for (attempt in 0..1) {
            try {
                val k = key ?: loadOrCreate().also { key = it }
                val bytes = Charsets.UTF_8.encode(CharBuffer.wrap(text))
                try {
                    return@synchronized Mac.getInstance(ALGORITHM).run {
                        init(k)
                        update(bytes)
                        doFinal()
                    }
                } finally {
                    if (bytes.hasArray()) bytes.array().fill(0)
                }
            } catch (_: Exception) {
                key = null
                if (attempt == 0) KeySpecs.deleteKeyQuietly(alias)
            }
        }
        null
    }

    private fun loadOrCreate(): SecretKey {
        (KeySpecs.keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("HmacSHA256", KeySpecs.ANDROID_KEYSTORE)
            .apply { init(KeySpecs.clipboardMacSpec(alias)) }
            .generateKey()
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
