package de.lulurobb.keypassmobile.ui.about

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.BigButton
import de.lulurobb.keypassmobile.ui.components.BigButtonStyle
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.NavAction
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SectionLabel
import de.lulurobb.keypassmobile.ui.components.SettingsRow
import de.lulurobb.keypassmobile.ui.components.SettingsSubline
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

/**
 * "Über KeyPass Mobile" (settings, row at the bottom): app facts, project links and the device,
 * grouped like the settings. Read-only rows are announced as one block of label and value; link
 * rows open [onOpenLink] (the browser). "Infos kopieren" hands the plain text summary to
 * [onCopyInfo]; it holds nothing secret.
 */
@Composable
fun AboutScreen(
    info: AboutInfo,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme

    val labelVersion = stringResource(R.string.about_version)
    val labelBuild = stringResource(R.string.about_build)
    val labelVariant = stringResource(R.string.about_variant)
    val labelPackage = stringResource(R.string.about_package)
    val labelCommit = stringResource(R.string.about_commit)
    val labelBuildDate = stringResource(R.string.about_build_date)
    val labelAndroid = stringResource(R.string.about_android)
    val labelDevice = stringResource(R.string.about_device)

    val localBuild = stringResource(R.string.about_local_build)
    val build = info.versionCode.toString()
    val variant = stringResource(
        when (info.variant) {
            BuildVariant.Release -> R.string.about_variant_release
            BuildVariant.Dev -> R.string.about_variant_dev
            BuildVariant.Debug -> R.string.about_variant_debug
        },
    )
    val commit = info.shortCommit ?: localBuild
    val buildDate = info.buildTime?.let { formatBuildTime(it) } ?: localBuild
    val android = stringResource(R.string.about_android_value, info.androidRelease, info.apiLevel)
    val device = info.device
    val appName = stringResource(R.string.app_name)
    val openInBrowser = stringResource(R.string.about_open_in_browser)

    Scaffold(
        modifier = modifier,
        topBar = {
            KpmTopBar(
                title = stringResource(R.string.settings_about),
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()),
        ) {
            SectionLabel(stringResource(R.string.about_group_app), topPadding = 8.dp)
            InfoRow(KpmIcons.Rocket, labelVersion, info.versionName)
            InfoRow(KpmIcons.Build, labelBuild, build)
            InfoRow(KpmIcons.Variant, labelVariant, variant)
            InfoRow(KpmIcons.Package, labelPackage, info.packageName, monospace = true)
            val commitUrl = info.commitUrl
            if (commitUrl != null) {
                LinkRow(KpmIcons.Commit, labelCommit, commit, commitUrl, openInBrowser, onOpenLink, monospace = true)
            } else {
                InfoRow(KpmIcons.Commit, labelCommit, commit)
            }
            InfoRow(KpmIcons.Schedule, labelBuildDate, buildDate)

            SectionLabel(stringResource(R.string.about_group_project))
            InfoRow(KpmIcons.Person, stringResource(R.string.about_creator), AboutLinks.CREATOR)
            LinkRow(
                KpmIcons.Code, stringResource(R.string.about_source), AboutLinks.REPOSITORY_DISPLAY,
                AboutLinks.REPOSITORY, openInBrowser, onOpenLink,
            )
            LinkRow(
                KpmIcons.Releases, stringResource(R.string.about_releases), stringResource(R.string.about_releases_text),
                AboutLinks.RELEASES, openInBrowser, onOpenLink,
            )
            LinkRow(
                KpmIcons.ReportProblem, stringResource(R.string.about_issues), stringResource(R.string.about_issues_text),
                AboutLinks.ISSUES, openInBrowser, onOpenLink,
            )

            SectionLabel(stringResource(R.string.about_group_device))
            InfoRow(KpmIcons.Android, labelAndroid, android)
            InfoRow(KpmIcons.Smartphone, labelDevice, device)

            val summary = aboutSummary(
                appName,
                listOf(
                    labelVersion to info.versionName,
                    labelBuild to build,
                    labelVariant to variant,
                    labelPackage to info.packageName,
                    labelCommit to commit,
                    labelBuildDate to buildDate,
                    labelAndroid to android,
                    labelDevice to device,
                ),
            )
            BigButton(
                text = stringResource(R.string.about_copy),
                onClick = { onCopyInfo(summary) },
                modifier = Modifier.padding(horizontal = ScreenPadding).padding(top = 28.dp, bottom = 32.dp),
                icon = KpmIcons.Copy,
                style = BigButtonStyle.Tonal,
            )
        }
    }
}

/** Read-only row; TalkBack reads label and value as one. */
@Composable
private fun InfoRow(@DrawableRes icon: Int, label: String, value: String, monospace: Boolean = false) {
    SettingsRow(
        icon = icon,
        title = label,
        modifier = Modifier.semantics(mergeDescendants = true) {},
        sublines = listOf(SettingsSubline(value, monospace)),
    )
}

/** Row that opens [url] in the browser; the tap is announced as [openLabel]. */
@Composable
private fun LinkRow(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    url: String,
    openLabel: String,
    onOpenLink: (String) -> Unit,
    monospace: Boolean = false,
) {
    SettingsRow(
        icon = icon,
        title = label,
        sublines = listOf(SettingsSubline(value, monospace)),
        onClick = { onOpenLink(url) },
        onClickLabel = openLabel,
        trailing = { KpmIcon(KpmIcons.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}
