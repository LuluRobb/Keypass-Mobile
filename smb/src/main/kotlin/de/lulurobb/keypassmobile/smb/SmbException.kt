package de.lulurobb.keypassmobile.smb

import java.io.IOException
import java.util.UUID

/**
 * The only exception type thrown by this module. The UI maps [reason] to a German
 * message; [message] is a short English description for diagnostics.
 *
 * Neither [message] nor [toString] ever contain secrets (SE-07): no password and no
 * message text of the underlying exception. The [cause] is kept for debugging; it
 * comes from smbj or the socket layer, which never see the password as text in their
 * messages.
 *
 * @property reason Category of the failure.
 * @property serverGuid For [Reason.SERVER_CHANGED]: the GUID the server sent instead of
 *   the expected one, so the app can ask the user and store it (DS-04). Null otherwise.
 */
class SmbException(
    val reason: Reason,
    message: String,
    val serverGuid: UUID? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {

    /** Failure categories. */
    enum class Reason {
        /** Host or port not reachable, connection refused or lost, or a timeout (DS-05). */
        UNREACHABLE,

        /** Login rejected: wrong user or password, disabled account, guest-only server. */
        AUTH_FAILED,

        /**
         * The server GUID differs from the expected one. The connection was closed after
         * the negotiate step, before any credentials were sent (DS-04).
         */
        SERVER_CHANGED,

        /** The share does not exist or is not a disk share. */
        SHARE_NOT_FOUND,

        /** The file or folder does not exist, or the path names the wrong kind of entry. */
        FILE_NOT_FOUND,

        /** The user may not access the share, folder or file (or list shares). */
        ACCESS_DENIED,

        /**
         * The server could not list its shares (SRVSVC pipe missing, RPC rejected or
         * failed). The UI falls back to typing the share name (DS-02).
         */
        SHARE_LISTING_UNSUPPORTED,

        /** Unexpected SMB status or malformed response, incomplete download. */
        PROTOCOL,

        /** Anything else, for example a local file error while writing a download. */
        OTHER,
    }

    override fun toString(): String = "SmbException(reason=$reason, message=$message" +
        (if (serverGuid != null) ", serverGuid=$serverGuid" else "") + ")"
}
