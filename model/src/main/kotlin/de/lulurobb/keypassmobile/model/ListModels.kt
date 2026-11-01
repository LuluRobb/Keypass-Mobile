package de.lulurobb.keypassmobile.model

/**
 * One section of the entry list (UI-01): all entries of one group, sorted A-Z.
 * Built by :core, shown by :app.
 */
class EntrySection(
    /** Full group path below the root group. Empty: the root group itself. */
    val groupPath: List<String>,
    val entries: List<VaultEntry>,
)

/** Where the autofill service is asked to fill (AF-05 to AF-07). */
sealed interface FillTarget {
    /** Login page in a browser. [host] is the web domain reported by the browser. */
    data class Web(val host: String, val browserPackage: String) : FillTarget

    /**
     * Native app. [appLabel] is only used for sorting, because every app can
     * choose its name freely; [packageName] is what the user is shown (AF-07).
     */
    data class App(val packageName: String, val appLabel: String?) : FillTarget
}
