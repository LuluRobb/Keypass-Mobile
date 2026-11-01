package de.lulurobb.keypassmobile.kdbx

/**
 * Opening a KDBX file failed. [message] is German, short and never contains
 * secrets or library messages (SE-07). The [cause] chain keeps exception
 * types and stack traces for debugging, but all messages in it are replaced
 * by the exception class names.
 */
class KdbxOpenException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Reason {
        /**
         * The key does not match: wrong master password, or the database also
         * needs a key file or YubiKey (not supported, KX-04). The app asks
         * for the master password again (EN-06).
         */
        WRONG_KEY,

        /** Valid KDBX file, but a version, cipher or KDF that is not supported. */
        UNSUPPORTED,

        /** Not a KDBX file, truncated or damaged. */
        CORRUPT,

        /** Anything else: file not readable, key derivation failed, out of memory. */
        OTHER,
    }
}

/**
 * Copy of an exception chain without messages: the message of each element
 * is its class name, the stack traces are kept. Used as cause of
 * [KdbxOpenException], because library messages may quote file content.
 */
internal class SanitizedException(original: Throwable, depth: Int = 0) :
    Exception(original.javaClass.name, sanitizedCause(original, depth)) {
    init {
        stackTrace = original.stackTrace
    }

    private companion object {
        const val MAX_DEPTH = 8

        fun sanitizedCause(original: Throwable, depth: Int): Throwable? {
            val cause = original.cause ?: return null
            if (cause === original || depth >= MAX_DEPTH) return null
            return SanitizedException(cause, depth + 1)
        }
    }
}
