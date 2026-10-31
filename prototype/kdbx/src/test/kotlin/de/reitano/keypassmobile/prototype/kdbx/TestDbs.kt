package de.reitano.keypassmobile.prototype.kdbx

/**
 * Test databases in src/test/resources, created by testdata/create-testdbs.ps1.
 * The passwords are test values only. Keep them in sync with the script.
 */
object TestDbs {
    const val PASSWORD = "kpm-Test-2026!"
    const val PASSWORD_UMLAUT = "Prüfung-Äß-2026"

    /** keepassxc-cli db-create/import without -t: KDBX 3.1, AES-KDF, AES-256. */
    const val CLI_DEFAULT = "cli-default.kdbx"

    /** Argon2d, AES-256, 64 MiB, parallelism 4, iterations like a 1 s benchmark of the GUI. */
    const val GUI_DEFAULT = "gui-default-argon2d.kdbx"

    const val ARGON2D_AES = "argon2d-aes.kdbx"
    const val ARGON2ID_AES = "argon2id-aes.kdbx"
    const val ARGON2D_CHACHA20 = "argon2d-chacha20.kdbx"
    const val ARGON2D_TWOFISH = "argon2d-twofish.kdbx"
    const val UMLAUT_PASSWORD = "argon2id-umlaut-password.kdbx"

    /** KDBX 4.1 features (tags, KP2A_URL*, notes, custom fields), Argon2d. */
    const val KDBX41_FEATURES = "kdbx41-features.kdbx"

    /** Same content, unmodified keepassxc-cli import: KDBX 4.1 with AES-KDF (-t 100). */
    const val KDBX41_AESKDF_KEEPASSXC = "kdbx41-aeskdf-keepassxc.kdbx"

    /** 3000 generated entries in 10 groups (see New-LargeXml in the script), Argon2d. */
    const val LARGE = "large-3000.kdbx"

    /** Entries of testdata/basic.xml, present in all files except the 4.1 and large ones. */
    const val BASIC_ENTRY_COUNT = 3

    fun bytes(name: String): ByteArray =
        requireNotNull(TestDbs::class.java.getResourceAsStream("/$name")) { "Testdatei fehlt: $name" }
            .use { it.readBytes() }

    fun password(value: String = PASSWORD): ByteArray = value.toByteArray(Charsets.UTF_8)
}
