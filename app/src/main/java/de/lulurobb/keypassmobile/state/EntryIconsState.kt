package de.lulurobb.keypassmobile.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.ui.icons.EntryIcons

/**
 * The [EntryIcons] of [vault]: one instance per vault (compared by identity, Vault is no
 * data class), cleared when the vault changes or the composition leaves, so no decoded
 * image outlives its vault (SE-04).
 */
@Composable
fun rememberEntryIcons(vault: Vault): EntryIcons {
    val icons = remember(vault) { EntryIcons(vault.customIcons) }
    DisposableEffect(icons) {
        onDispose { icons.clear() }
    }
    return icons
}
