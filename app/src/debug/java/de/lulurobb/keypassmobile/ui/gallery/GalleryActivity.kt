package de.lulurobb.keypassmobile.ui.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme

/**
 * Debug-only gallery of every screen and state with fake data. Not secure
 * (no FLAG_SECURE), so screenshots work. Extras: "scenario" (id from
 * [Scenarios]) and "theme" (light, dark, system).
 */
class GalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startScenario = intent.getStringExtra("scenario")
        val startTheme = intent.getStringExtra("theme") ?: "system"
        setContent {
            var theme by rememberSaveable { mutableStateOf(startTheme) }
            var scenario by rememberSaveable { mutableStateOf(startScenario) }
            val dark = when (theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            KeyPassTheme(darkTheme = dark) {
                val current = scenario?.let { id -> Scenarios.all.firstOrNull { it.id == id } }
                if (current == null) {
                    GalleryHome(theme, onTheme = { theme = it }, onOpen = { scenario = it })
                } else {
                    BackHandler { scenario = null }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        current.content { scenario = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryHome(theme: String, onTheme: (String) -> Unit, onOpen: (String) -> Unit) {
    val themes = listOf("system" to "System", "light" to "Hell", "dark" to "Dunkel")
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(ScreenPadding)) {
                    Text("KPM Galerie", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Alle Bildschirme und Zustände mit Beispieldaten. Zurück führt zur Liste.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        themes.forEachIndexed { i, (key, label) ->
                            SegmentedButton(
                                selected = theme == key,
                                onClick = { onTheme(key) },
                                shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
            Scenarios.all.groupBy { it.group }.forEach { (group, list) ->
                item(key = "h/$group") {
                    Text(
                        group,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = ScreenPadding, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.id }) { s ->
                    Text(
                        s.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(s.id) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = ScreenPadding, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
