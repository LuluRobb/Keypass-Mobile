package de.lulurobb.keypassmobile.ui.icons

import androidx.annotation.DrawableRes
import de.lulurobb.keypassmobile.R

/**
 * KeePass standard icons (numbers 0 to 68, as used by KeePassXC) drawn with
 * Material Symbols (UI-05). The KeePass name of each icon is given in the
 * comment. Brand icons (Tux, Apple, BlackBerry) map to neutral devices.
 */
object KeePassIcons {

    private val map: IntArray = intArrayOf(
        R.drawable.ic_key, // 0 Key
        R.drawable.ic_public, // 1 World
        R.drawable.ic_warning, // 2 Warning
        R.drawable.ic_router, // 3 NetworkServer
        R.drawable.ic_folder_special, // 4 MarkedDirectory
        R.drawable.ic_forum, // 5 UserCommunication
        R.drawable.ic_extension, // 6 Parts
        R.drawable.ic_edit_note, // 7 Notepad
        R.drawable.ic_lan, // 8 WorldSocket
        R.drawable.ic_person, // 9 Identity
        R.drawable.ic_description, // 10 PaperReady
        R.drawable.ic_photo_camera, // 11 Digicam
        R.drawable.ic_settings_remote, // 12 IRCommunication
        R.drawable.ic_vpn_key, // 13 MultiKeys
        R.drawable.ic_bolt, // 14 Energy
        R.drawable.ic_scanner, // 15 Scanner
        R.drawable.ic_travel_explore, // 16 WorldStar
        R.drawable.ic_album, // 17 CDRom
        R.drawable.ic_monitor, // 18 Monitor
        R.drawable.ic_mail, // 19 EMail
        R.drawable.ic_settings, // 20 Configuration
        R.drawable.ic_content_paste, // 21 ClipboardReady
        R.drawable.ic_note_add, // 22 PaperNew
        R.drawable.ic_desktop_windows, // 23 Screen
        R.drawable.ic_power, // 24 EnergyCareful
        R.drawable.ic_inbox, // 25 EMailBox
        R.drawable.ic_save, // 26 Disk
        R.drawable.ic_storage, // 27 Drive
        R.drawable.ic_quiz, // 28 PaperQ
        R.drawable.ic_enhanced_encryption, // 29 TerminalEncrypted
        R.drawable.ic_terminal, // 30 Console
        R.drawable.ic_print, // 31 Printer
        R.drawable.ic_apps, // 32 ProgramIcons
        R.drawable.ic_rocket_launch, // 33 Run
        R.drawable.ic_tune, // 34 Settings
        R.drawable.ic_dns, // 35 WorldComputer
        R.drawable.ic_archive, // 36 Archive
        R.drawable.ic_account_balance, // 37 Homebanking
        R.drawable.ic_hard_drive, // 38 DriveWindows
        R.drawable.ic_schedule, // 39 Clock
        R.drawable.ic_manage_search, // 40 EMailSearch
        R.drawable.ic_flag, // 41 PaperFlag
        R.drawable.ic_memory, // 42 Memory
        R.drawable.ic_delete, // 43 TrashBin
        R.drawable.ic_sticky_note_2, // 44 Note
        R.drawable.ic_cancel, // 45 Expired
        R.drawable.ic_info, // 46 Info
        R.drawable.ic_package_2, // 47 Package
        R.drawable.ic_folder, // 48 Folder
        R.drawable.ic_folder_open, // 49 FolderOpen
        R.drawable.ic_folder_zip, // 50 FolderPackage
        R.drawable.ic_lock_open, // 51 LockOpen
        R.drawable.ic_encrypted, // 52 PaperLocked
        R.drawable.ic_check_circle, // 53 Checked
        R.drawable.ic_edit, // 54 Pen
        R.drawable.ic_image, // 55 Thumbnail
        R.drawable.ic_book, // 56 Book
        R.drawable.ic_list, // 57 List
        R.drawable.ic_badge, // 58 UserKey
        R.drawable.ic_build, // 59 Tool
        R.drawable.ic_home, // 60 Home
        R.drawable.ic_star, // 61 Star
        R.drawable.ic_computer, // 62 Tux
        R.drawable.ic_history_edu, // 63 Feather
        R.drawable.ic_laptop_mac, // 64 Apple
        R.drawable.ic_menu_book, // 65 Wiki
        R.drawable.ic_payments, // 66 Money
        R.drawable.ic_verified_user, // 67 Certificate
        R.drawable.ic_smartphone, // 68 BlackBerry
    )

    /** Drawable for a KeePass icon number; unknown numbers fall back to the key. */
    @DrawableRes
    fun forId(iconId: Int): Int = map.getOrElse(iconId) { R.drawable.ic_key }
}
