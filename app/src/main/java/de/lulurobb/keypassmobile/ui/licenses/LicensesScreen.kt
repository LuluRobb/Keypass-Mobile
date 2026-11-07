package de.lulurobb.keypassmobile.ui.licenses

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.NavAction
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SettingsRow
import de.lulurobb.keypassmobile.ui.components.SettingsSubline
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

/**
 * "Open-Source-Lizenzen" (About screen, group "Rechtliches"): every library and data set in the app,
 * sorted by name, with version and license. A tap opens the detail page via [onOpen]. The list state
 * is created only once the entries exist, so a scroll position saved by the screen state holder is
 * restored on the real list.
 */
@Composable
fun LicensesScreen(
    state: LicenseListState,
    onBack: () -> Unit,
    onOpen: (LicenseEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            KpmTopBar(
                title = stringResource(R.string.about_licenses),
                navigation = NavAction(KpmIcons.Back, stringResource(R.string.action_back), onBack),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        when (state) {
            LicenseListState.Loading -> {
                val loading = stringResource(R.string.licenses_loading)
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    // The description is what TalkBack announces for the spinner.
                    CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
                }
            }
            LicenseListState.Failed -> Text(
                stringResource(R.string.licenses_load_failed),
                modifier = Modifier.fillMaxWidth().padding(padding).padding(ScreenPadding),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is LicenseListState.Loaded -> LicenseList(state.entries, onOpen, Modifier.padding(padding))
        }
    }
}

@Composable
private fun LicenseList(entries: List<LicenseEntry>, onOpen: (LicenseEntry) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + bottomInset),
    ) {
        items(entries, key = { it.id }) { entry ->
            SettingsRow(
                icon = null,
                title = entry.name,
                sublines = entry.summary.takeIf { it.isNotEmpty() }?.let { listOf(SettingsSubline(it)) }.orEmpty(),
                onClick = { onOpen(entry) },
                trailing = { KpmIcon(KpmIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}
