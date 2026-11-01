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
    @DrawableRes val ChevronRight = R.drawable.ic_chevron_right
    @DrawableRes val Folder = R.drawable.ic_folder
    @DrawableRes val FolderShared = R.drawable.ic_folder_shared
    @DrawableRes val ExpandMore = R.drawable.ic_expand_more
    @DrawableRes val ExpandLess = R.drawable.ic_expand_less
    @DrawableRes val Notifications = R.drawable.ic_notifications
    @DrawableRes val Keyboard = R.drawable.ic_keyboard
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
