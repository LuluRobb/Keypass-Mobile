package de.reitano.keypassmobile.prototype.kdbx

import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.KdfParameters
import okio.ByteString.Companion.toByteString

enum class Argon2Variant { Argon2d, Argon2id }

/**
 * Argon2 parameters from a KDBX header, in the units native libraries use
 * (memory in KiB instead of bytes as stored in the file).
 */
class Argon2Params(
    val variant: Argon2Variant,
    /** 0x10 or 0x13. */
    val version: Int,
    val memoryKiB: Long,
    val iterations: Long,
    val parallelism: Int,
    val salt: ByteArray,
    /** Optional Argon2 inputs K and A. KeePassXC never sets them. */
    val secret: ByteArray?,
    val associatedData: ByteArray?,
) {
    val usesSecretOrAssociatedData: Boolean
        get() = (secret?.isNotEmpty() ?: false) || (associatedData?.isNotEmpty() ?: false)
}

/**
 * Pluggable Argon2 implementation, e.g. a native library on Android.
 * Must return the 32-byte raw hash of [password] (the KDBX composite key).
 */
fun interface Argon2Function {
    fun hash(password: ByteArray, params: Argon2Params): ByteArray
}

/**
 * kotpass' default KdfProvider (pure Kotlin, single-threaded Argon2). The
 * class and all KDF implementations are Kotlin-internal in kotpass 0.13.0,
 * so the singleton is looked up by name. Needs a keep rule if R8 is enabled.
 */
internal val kotpassKdf: KdfProvider by lazy {
    Class.forName("app.keemobile.kotpass.cryptography.format.BaseKdfProvider")
        .getField("INSTANCE")
        .get(null) as KdfProvider
}

/** kotpass' own Argon2 as [Argon2Function], e.g. for benchmarks and tests. */
object KotpassArgon2 : Argon2Function {
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
        return kotpassKdf.transformKey(kdf, password)
    }
}

/**
 * KdfProvider that measures the key derivation and optionally replaces
 * kotpass' Argon2 with [argon2]. kotpass calls it once per decode, before
 * decrypting anything.
 */
internal class MeasuringKdfProvider(private val argon2: Argon2Function?) : KdfProvider {
    var nanos: Long = 0
        private set
    var info: KdfInfo? = null
        private set
    var implementation: String = KOTPASS
        private set

    override fun transformKey(kdfParameters: KdfParameters, compositeKey: ByteArray): ByteArray {
        val start = System.nanoTime()
        try {
            when (kdfParameters) {
                is KdfParameters.Argon2 -> {
                    val params = kdfParameters.toArgon2Params()
                    info = KdfInfo.Argon2(
                        variant = params.variant,
                        memoryKiB = params.memoryKiB,
                        iterations = params.iterations,
                        parallelism = params.parallelism,
                        version = params.version,
                    )
                    if (argon2 != null && !params.usesSecretOrAssociatedData) {
                        implementation = EXTERNAL
                        return argon2.hash(compositeKey, params)
                    }
                }
                is KdfParameters.Aes -> info = KdfInfo.AesKdf(kdfParameters.rounds.toLong())
            }
            return kotpassKdf.transformKey(kdfParameters, compositeKey)
        } finally {
            nanos += System.nanoTime() - start
        }
    }

    companion object {
        const val KOTPASS = "kotpass (reines Kotlin)"
        const val EXTERNAL = "extern (Argon2Function)"
    }
}

internal fun KdfParameters.Argon2.toArgon2Params() = Argon2Params(
    variant = when (variant) {
        KdfParameters.Argon2.Variant.Argon2d -> Argon2Variant.Argon2d
        KdfParameters.Argon2.Variant.Argon2id -> Argon2Variant.Argon2id
    },
    version = version.toInt(),
    memoryKiB = memory.toLong() / 1024,
    iterations = iterations.toLong(),
    parallelism = parallelism.toInt(),
    salt = salt.toByteArray(),
    secret = secretKey?.toByteArray(),
    associatedData = associatedData?.toByteArray(),
)
