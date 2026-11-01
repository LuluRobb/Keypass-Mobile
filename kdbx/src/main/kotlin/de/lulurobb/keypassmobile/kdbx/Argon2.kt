package de.lulurobb.keypassmobile.kdbx

import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.KdfParameters
import okio.ByteString.Companion.toByteString

/** Argon2 variants used by KDBX 4 (KeePassXC writes Argon2d by default). */
enum class Argon2Variant { Argon2d, Argon2id }

/**
 * Argon2 parameters of one KDBX file, converted to the units native Argon2
 * libraries use (memory in KiB, while the file stores bytes).
 *
 * The output length is always [HASH_LENGTH] bytes. Not a data class, so the
 * optional [secret] never ends up in a generated toString (SE-07).
 */
class Argon2Params(
    val variant: Argon2Variant,
    /** Argon2 version, 0x10 or 0x13 (KeePassXC writes 0x13). */
    val version: Int,
    /** Memory cost in KiB. */
    val memoryKiB: Long,
    /** Time cost (number of passes). */
    val iterations: Long,
    /** Number of lanes. Native implementations should use as many threads. */
    val parallelism: Int,
    /** Salt from the file header (32 bytes for KeePassXC). Not secret. */
    val salt: ByteArray,
    /** Optional Argon2 secret input K. KeePassXC never sets it. */
    val secret: ByteArray?,
    /** Optional Argon2 associated data X. KeePassXC never sets it. */
    val associatedData: ByteArray?,
) {
    /**
     * True if the file uses the optional inputs K or X. Such files are always
     * derived with the kotpass implementation, because common native
     * libraries (e.g. argon2kt) do not accept them.
     */
    val usesSecretOrAssociatedData: Boolean
        get() = (secret?.isNotEmpty() ?: false) || (associatedData?.isNotEmpty() ?: false)

    override fun toString(): String =
        "Argon2Params($variant, v=0x${version.toString(16)}, m=$memoryKiB KiB, t=$iterations, p=$parallelism)"

    companion object {
        /** Length of the derived key in bytes (the KDBX transformed key). */
        const val HASH_LENGTH = 32
    }
}

/**
 * Pluggable Argon2 implementation for the KDBX key derivation.
 *
 * kotpass computes Argon2 in single-threaded pure Kotlin, which is about 9x
 * too slow on the target phone. The Android app therefore passes a native
 * implementation (argon2kt) to [KdbxReader]. The JVM tests use the default
 * [KotpassArgon2Engine].
 *
 * Contract:
 * - Return the raw Argon2 hash of [hash]'s `password` with exactly
 *   [Argon2Params.HASH_LENGTH] bytes (not the encoded string form).
 * - `password` is the 32-byte KDBX composite key. It is secret: do not keep,
 *   log or copy it beyond the call. kotpass overwrites it after the call.
 * - Throw any exception on failure (e.g. out of memory). [KdbxReader] reports
 *   it as [KdbxOpenException.Reason.OTHER] without the original message.
 * - Called on the thread that calls [KdbxReader.open], once per open.
 */
fun interface Argon2Engine {
    fun hash(password: ByteArray, params: Argon2Params): ByteArray
}

/**
 * kotpass' own pure-Kotlin Argon2 as [Argon2Engine]. Default of [KdbxReader]
 * and used by the JVM tests; slow on phones.
 */
object KotpassArgon2Engine : Argon2Engine {
    override fun hash(password: ByteArray, params: Argon2Params): ByteArray {
        val kdf = KdfParameters.Argon2.default(params.salt.toByteString()).copy(
            variant = when (params.variant) {
                Argon2Variant.Argon2d -> KdfParameters.Argon2.Variant.Argon2d
                Argon2Variant.Argon2id -> KdfParameters.Argon2.Variant.Argon2id
            },
            version = params.version.toUInt(),
            memory = params.memoryKiB.toULong() * 1024u,
            iterations = params.iterations.toULong(),
            parallelism = params.parallelism.toUInt(),
            secretKey = params.secret?.toByteString(),
            associatedData = params.associatedData?.toByteString(),
        )
        return KotpassInternals.baseKdfProvider.transformKey(kdf, password)
    }
}

/** Thrown inside the key derivation; reported as [KdbxOpenException.Reason.OTHER]. */
internal class KdfFailure(cause: Throwable) : Exception("Schlüsselableitung fehlgeschlagen.", cause)

/**
 * KdfProvider handed to kotpass. Uses [engine] for Argon2 (unless the file
 * uses Argon2 secret or associated data) and kotpass for everything else
 * (AES-KDF). kotpass calls it once per decode, before decrypting anything.
 */
internal class EngineKdfProvider(private val engine: Argon2Engine?) : KdfProvider {

    override fun transformKey(kdfParameters: KdfParameters, compositeKey: ByteArray): ByteArray {
        try {
            if (engine != null && kdfParameters is KdfParameters.Argon2) {
                val params = kdfParameters.toArgon2Params()
                if (!params.usesSecretOrAssociatedData) {
                    val result = engine.hash(compositeKey, params)
                    if (result.size != Argon2Params.HASH_LENGTH) {
                        result.fill(0)
                        throw IllegalStateException("Argon2 lieferte eine falsche Länge.")
                    }
                    return result
                }
            }
            return KotpassInternals.baseKdfProvider.transformKey(kdfParameters, compositeKey)
        } catch (e: Exception) {
            throw KdfFailure(e)
        }
    }
}

internal fun KdfParameters.Argon2.toArgon2Params() = Argon2Params(
    variant = when (variant) {
        KdfParameters.Argon2.Variant.Argon2d -> Argon2Variant.Argon2d
        KdfParameters.Argon2.Variant.Argon2id -> Argon2Variant.Argon2id
    },
    version = version.toInt(),
    memoryKiB = (memory / 1024u).toLong(),
    iterations = iterations.toLong(),
    parallelism = parallelism.toInt(),
    salt = salt.toByteArray(),
    secret = secretKey?.toByteArray(),
    associatedData = associatedData?.toByteArray(),
)
