package de.lulurobb.keypassmobile.platform

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import de.lulurobb.keypassmobile.core.sync.RemoteSource
import java.io.File
import java.time.Instant

/** Password of the test databases (test value only, see kdbx/testdata/create-testdbs.ps1). */
const val TEST_DB_PASSWORD = "kpm-Test-2026!"

/** The library under test runs inside the test APK, so this is also the app context. */
val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** Copies an asset of the test APK into the cache directory. */
fun asset(name: String): File {
    val target = File(context.cacheDir, "assets/$name")
    target.parentFile!!.mkdirs()
    InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
        target.outputStream().use { input.copyTo(it) }
    }
    return target
}

/** A local file as [RemoteSource], to feed the sync engine without SMB. */
class LocalRemote(private val file: File, private val modified: Instant = Instant.parse("2026-10-31T12:00:00Z")) : RemoteSource {
    override fun stat() = RemoteStat(file.length(), modified)

    override fun download(target: File) {
        file.copyTo(target, overwrite = true)
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** Polls [condition] for up to [timeoutMillis]. */
fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        Thread.sleep(50)
    }
    return condition()
}
