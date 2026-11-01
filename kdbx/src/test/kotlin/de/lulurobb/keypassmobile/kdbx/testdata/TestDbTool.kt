package de.lulurobb.keypassmobile.kdbx.testdata

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.Meta
import de.lulurobb.keypassmobile.kdbx.TestDbs
import okio.ByteString.Companion.toByteString
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * Helper for testdata/create-testdbs.ps1, run via the Gradle task
 * :kdbx:createTestDbTargets. keepassxc-cli 2.7 cannot choose KDF or cipher,
 * so this writes an empty KDBX 4.0 database with the requested settings. The
 * script then merges KeePassXC-created content into it with keepassxc-cli,
 * which makes KeePassXC rewrite the whole file with its own crypto code.
 *
 * One argument per file: "path,kdf,cipher,iterations,memoryKiB,parallelism,password"
 * with kdf argon2d|argon2id, cipher aes|chacha20|twofish, password default|umlaut.
 * Paths are relative to the module directory.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "No target specs given" }
    val random = SecureRandom()
    for (spec in args) {
        val parts = spec.split(',')
        require(parts.size == 7) { "Invalid spec: $spec" }
        val path = parts[0]
        val kdf = parts[1]
        val cipher = parts[2]
        val iterations = parts[3]
        val memoryKiB = parts[4]
        val parallelism = parts[5]
        val password = when (parts[6]) {
            "default" -> TestDbs.PASSWORD
            "umlaut" -> TestDbs.PASSWORD_UMLAUT
            else -> error("Unknown password id: ${parts[6]}")
        }
        val variant = when (kdf) {
            "argon2d" -> KdfParameters.Argon2.Variant.Argon2d
            "argon2id" -> KdfParameters.Argon2.Variant.Argon2id
            else -> error("Unknown kdf: $kdf")
        }
        val cipherId = when (cipher) {
            "aes" -> BaseCiphers.Aes.uuid
            "chacha20" -> BaseCiphers.ChaCha20.uuid
            "twofish" -> TwofishCipher.uuid
            else -> error("Unknown cipher: $cipher")
        }
        writeTarget(
            file = File(path),
            password = password,
            cipherId = cipherId,
            kdf = KdfParameters.Argon2.default(ByteArray(32).also(random::nextBytes).toByteString()).copy(
                variant = variant,
                iterations = iterations.toULong(),
                memory = memoryKiB.toULong() * 1024u,
                parallelism = parallelism.toUInt(),
            ),
            random = random,
        )
        println("Target written: $path ($kdf, $cipher, i=$iterations, m=$memoryKiB KiB, p=$parallelism)")
    }
}

private fun writeTarget(file: File, password: String, cipherId: UUID, kdf: KdfParameters, random: SecureRandom) {
    val credentials = Credentials.from(EncryptedValue.fromString(password))
    val base = KeePassDatabase.Ver4x.create("Root", Meta(generator = "kpm-testdata"), credentials, random)
    val database = base.copy(
        header = base.header.copy(version = FormatVersion(4, 0), cipherId = cipherId, kdfParameters = kdf),
    )
    file.parentFile?.mkdirs()
    file.outputStream().use { out ->
        database.encode(out, cipherProviders = BaseCiphers.entries + TwofishCipher)
    }
}
