package de.lulurobb.keypassmobile.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import de.lulurobb.keypassmobile.R

/*
 * Icons are vector drawables copied from Material Symbols (Outlined, FILL 1,
 * weight 400, Apache-2.0) into res/drawable. That keeps the APK small compared
 * to the material-icons-extended artifact and gives one consistent icon family.
 */

/** Icons used by the app chrome. */
object KpmIcons {
    @DrawableRes val Search = R.drawable.ic_search
    @DrawableRes val Lock = R.drawable.ic_lock
    @DrawableRes val Settings = R.drawable.ic_settings
    @DrawableRes val Back = R.drawable.ic_arrow_back
    @DrawableRes val Close = R.drawable.ic_close
    @DrawableRes val Copy = R.drawable.ic_content_copy
    @DrawableRes val Fingerprint = R.drawable.ic_fingerprint
    @DrawableRes val Sync = R.drawable.ic_sync
    @DrawableRes val CheckCircle = R.drawable.ic_check_circle
    @DrawableRes val Check = R.drawable.ic_check
    @DrawableRes val CloudOff = R.drawable.ic_cloud_off
    @DrawableRes val Warning = R.drawable.ic_warning
    @DrawableRes val Error = R.drawable.ic_error
    @DrawableRes val Info = R.drawable.ic_info
    @DrawableRes val Visibility = R.drawable.ic_visibility
    @DrawableRes val VisibilityOff = R.drawable.ic_visibility_off
    @DrawableRes val Key = R.drawable.ic_key
    @DrawableRes val Server = R.drawable.ic_dns

    /** A file on the phone as source (document with text lines). */
    @DrawableRes val File = R.drawable.ic_description

    /** "Quelle verwalten" row (sliders). */
    @DrawableRes val Tune = R.drawable.ic_tune
    @DrawableRes val ChevronRight = R.drawable.ic_chevron_right
    @DrawableRes val Folder = R.drawable.ic_folder
    @DrawableRes val FolderShared = R.drawable.ic_folder_shared
    @DrawableRes val ExpandMore = R.drawable.ic_expand_more
    @DrawableRes val ExpandLess = R.drawable.ic_expand_less
    @DrawableRes val Notifications = R.drawable.ic_notifications
    @DrawableRes val Keyboard = R.drawable.ic_keyboard

    /** Trailing icon of a row that leaves the app for the browser. */
    @DrawableRes val OpenInNew = R.drawable.ic_open_in_new

    /* Icons of the About screen. */
    @DrawableRes val Rocket = R.drawable.ic_rocket_launch
    @DrawableRes val Build = R.drawable.ic_build
    @DrawableRes val Variant = R.drawable.ic_extension
    @DrawableRes val Package = R.drawable.ic_package_2
    @DrawableRes val Commit = R.drawable.ic_commit
    @DrawableRes val Schedule = R.drawable.ic_schedule
    @DrawableRes val Person = R.drawable.ic_person
    @DrawableRes val Code = R.drawable.ic_terminal
    @DrawableRes val Releases = R.drawable.ic_archive
    @DrawableRes val ReportProblem = R.drawable.ic_flag
    @DrawableRes val Android = R.drawable.ic_memory
    @DrawableRes val Smartphone = R.drawable.ic_smartphone

    /* Icons of the licenses pages ("Rechtliches" row of the About screen and the detail page). */
    @DrawableRes val Licenses = R.drawable.ic_description
    @DrawableRes val Website = R.drawable.ic_public
    @DrawableRes val Organization = R.drawable.ic_badge
}

/** An icon from res/drawable, tinted with the current content color. */
@Composable
fun KpmIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
