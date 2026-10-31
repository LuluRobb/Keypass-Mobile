package de.reitano.keypassmobile.prototype.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared screen layout: title row with optional back button, then the content
 * in a padded column. Handles system bar insets (edge-to-edge).
 *
 * With [scrollable] = true (default) the content column scrolls vertically;
 * pass false when the screen uses its own lazy list.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold { innerPadding ->
        val base = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        val column = if (scrollable) base.verticalScroll(rememberScrollState()) else base
        Column(
            modifier = column.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text("Zurück") }
                }
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
            }
            content()
        }
    }
}
