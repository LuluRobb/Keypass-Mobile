package de.lulurobb.keypassmobile.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.icons.KpmIcon

/** Position in the first-run setup ("Schritt 2 von 4"). */
data class SetupStep(val number: Int, val total: Int = SETUP_STEPS) {
    companion object {
        /** Server, file, master password, system settings. */
        const val SETUP_STEPS = 4
    }
}

/** Navigation icon of a [KpmTopBar]. */
data class NavAction(@DrawableRes val icon: Int, val description: String, val onClick: () -> Unit)

/** Material top app bar on the surface color, with an optional setup step at the end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KpmTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: NavAction? = null,
    step: SetupStep? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (navigation != null) {
                IconButton(onClick = navigation.onClick) {
                    KpmIcon(navigation.icon, contentDescription = navigation.description)
                }
            }
        },
        actions = {
            actions()
            if (step != null) {
                Text(
                    stringResource(R.string.step_of, step.number, step.total),
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
