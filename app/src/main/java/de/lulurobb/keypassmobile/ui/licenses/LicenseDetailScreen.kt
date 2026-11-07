package de.lulurobb.keypassmobile.ui.licenses

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.about.InfoRow
import de.lulurobb.keypassmobile.ui.about.LinkRow
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.NavAction
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SectionLabel
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

/**
 * Detail page of one library: description, version, license, developers, the website as a link row
 * (opens [onOpenLink], the browser) and the full license text. [entry] is null while the list is
 * not loaded (or the id is unknown): the page then has its top bar and a one-line notice.
 */
@Composable
fun LicenseDetailScreen(
    entry: LicenseEntry?,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        topBar = {
            KpmTopBar(
                title = entry?.name ?: stringResource(R.string.about_licenses),
                navigation = NavAction(KpmIcons.Back, stringResource(R.string.action_back), onBack),
            )
        },
        snackbarHost = {
            MessageHost(
                message,
                onMessageDismissed,
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        containerColor = scheme.surface,
    ) { padding ->
        if (entry == null) {
            Text(
                stringResource(R.string.licenses_load_failed),
                modifier = Modifier.fillMaxWidth().padding(padding).padding(ScreenPadding),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()),
        ) {
            if (entry.description != null) {
                Text(
                    entry.description,
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(top = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (entry.version != null) InfoRow(KpmIcons.Rocket, stringResource(R.string.license_version), entry.version)
            if (entry.licenseNames.isNotEmpty()) {
                InfoRow(KpmIcons.Licenses, stringResource(R.string.license_license), entry.licenseLabel)
            }
            if (entry.developers.isNotEmpty()) {
                InfoRow(KpmIcons.Person, stringResource(R.string.license_developer), entry.developers.joinToString(", "))
            }
            if (entry.organization != null) {
                InfoRow(KpmIcons.Organization, stringResource(R.string.license_organization), entry.organization)
            }
            if (entry.website != null) {
                LinkRow(
                    KpmIcons.Website, stringResource(R.string.license_website), displayAddress(entry.website),
                    entry.website, stringResource(R.string.about_open_in_browser), onOpenLink,
                )
            }

            SectionLabel(stringResource(R.string.license_text_heading))
            Text(
                entry.licenseText.ifEmpty { stringResource(R.string.license_text_missing) },
                modifier = Modifier.padding(horizontal = ScreenPadding).padding(top = 4.dp, bottom = 32.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
    }
}

/** The address without scheme and trailing slash, as shown in the link row. */
internal fun displayAddress(url: String): String = url.substringAfter("://").trimEnd('/')
