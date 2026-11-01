package de.lulurobb.keypassmobile.ui.icons

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.model.VaultEntry
import java.util.UUID

/**
 * Own icons of the unlocked database (Vault.customIcons), decoded on first use
 * and cached (UI-05). Create one per unlocked vault and drop it on lock, so no
 * decoded image outlives the vault (SE-04). Nothing is loaded from the network.
 */
class EntryIcons(private val customIcons: Map<UUID, ByteArray>) {

    private val cache = object : LruCache<UUID, Decoded>(MAX_CACHED) {}

    /** Decoded icon, or null when the id is unknown or the bytes are no image. */
    fun bitmap(id: UUID): ImageBitmap? {
        cache.get(id)?.let { return it.bitmap }
        val bytes = customIcons[id] ?: return null
        val decoded = Decoded(decode(bytes))
        cache.put(id, decoded)
        return decoded.bitmap
    }

    /** Drops all decoded images. */
    fun clear() = cache.evictAll()

    private class Decoded(val bitmap: ImageBitmap?)

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    companion object {
        private const val MAX_CACHED = 256
        private const val MAX_SIDE_PX = 128

        /** For screens without own icons (and previews). */
        val None = EntryIcons(emptyMap())
    }
}

/**
 * Icon of an entry: the own icon from the database when present, else the
 * mapped KeePass standard icon in the variant on-surface color.
 */
@Composable
fun EntryIcon(
    entry: VaultEntry,
    icons: EntryIcons,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
) {
    val custom = entry.customIconId?.let { id -> remember(icons, id) { icons.bitmap(id) } }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (custom != null) {
            Image(
                bitmap = custom,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                filterQuality = FilterQuality.Medium,
            )
        } else {
            KpmIcon(
                id = KeePassIcons.forId(entry.iconId),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
