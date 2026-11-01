package de.lulurobb.keypassmobile.kdbx

import java.io.File

/**
 * Test databases in src/test/resources, created by testdata/create-testdbs.ps1
 * and testdata/create-structure-testdb.ps1. The passwords are test values
 * only. Keep them in sync with the scripts.
 */
object TestDbs {
    const val PASSWORD = "kpm-Test-2026!"
    const val PASSWORD_UMLAUT = "Prüfung-Äß-2026"

    /** keepassxc-cli db-create/import without -t: KDBX 3.1, AES-KDF, AES-256. */
    const val CLI_DEFAULT = "cli-default.kdbx"

    /** Argon2d, AES-256, 64 MiB, parallelism 4, 100 iterations like the KeePassXC GUI default. */
    const val GUI_DEFAULT = "gui-default-argon2d.kdbx"

    const val ARGON2D_AES = "argon2d-aes.kdbx"
    const val ARGON2ID_AES = "argon2id-aes.kdbx"
    const val ARGON2D_CHACHA20 = "argon2d-chacha20.kdbx"
    const val ARGON2D_TWOFISH = "argon2d-twofish.kdbx"
    const val UMLAUT_PASSWORD = "argon2id-umlaut-password.kdbx"

    /** KDBX 4.1 features (tags, KP2A_URL*, notes, custom fields, custom icon), Argon2d. */
    const val KDBX41_FEATURES = "kdbx41-features.kdbx"

    /** Same content, unmodified keepassxc-cli import: KDBX 4.1 with AES-KDF (-t 100). */
    const val KDBX41_AESKDF_KEEPASSXC = "kdbx41-aeskdf-keepassxc.kdbx"

    /** 3000 generated entries in 10 groups, Argon2d. */
    const val LARGE = "large-3000.kdbx"

    /**
     * Groups, icons, recycle bin, templates group and history, all written by
     * KeePassXC (import of testdata/structure.xml, then rm, rmdir and edit
     * with keepassxc-cli). KDBX 4.1 with AES-KDF.
     */
    const val STRUCTURE = "keepassxc-structure.kdbx"

    /** Entries of testdata/basic.xml. */
    const val BASIC_ENTRY_COUNT = 3

    fun file(name: String): File {
        val url = requireNotNull(TestDbs::class.java.getResource("/$name")) { "Testdatei fehlt: $name" }
        return File(url.toURI())
    }

    fun bytes(name: String): ByteArray = file(name).readBytes()

    fun hash(password: String = PASSWORD): ByteArray = PasswordKey.hash(password.toCharArray())
}
