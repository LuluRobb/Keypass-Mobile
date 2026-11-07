package de.lulurobb.keypassmobile.ui.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * The system file picker (Storage Access Framework) for the database file (DS-08, DS-10): ACTION_OPEN_DOCUMENT
 * for any openable file on the phone (every MIME type, the type of a KeePass database is not reliable), local
 * storage only (no cloud providers), with a lasting read permission for the result.
 *
 * The input is the document URI where the picker starts (EXTRA_INITIAL_URI), or null for the default place.
 * The result is the document URI of the picked file, null when the user went back.
 */
class OpenKdbxDocument : ActivityResultContract<String?, Uri?>() {

    override fun createIntent(context: Context, input: String?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(input))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
