package de.lulurobb.keypassmobile.autofill.form

import de.lulurobb.keypassmobile.model.FillTarget

/**
 * Result of analysing one fill request: where to fill and which visible
 * fields get the username and the password. At least one id is set.
 */
internal class FillPlan<out Id : Any>(
    /** Package of the requesting app (the browser for web pages). */
    val packageName: String,
    /** Host of the page the login fields belong to; null in native apps. */
    val webDomain: String?,
    /** Scheme of that page ("https") when reported by the browser. */
    val webScheme: String?,
    val usernameId: Id?,
    val passwordId: Id?,
    /** All text fields of the structure, for debug logs and tests. */
    val fields: List<ParsedField<Id>>,
    /** Indices into [fields] of the chosen username and password fields. */
    val login: LoginFields,
) {
    /**
     * Fill target for the picker (AF-05 to AF-07): the web page when the
     * login fields carry a web domain, otherwise the app. [appLabel] is the
     * label of [packageName]; it is only used for app targets.
     */
    fun target(appLabel: String?): FillTarget = fillTargetOf(packageName, webDomain, appLabel)
}

/**
 * Web target when [webDomain] is set (browser or WebView), otherwise the app.
 * [appLabel] is only kept for app targets; for web targets it is the browser
 * label, which the picker gets separately.
 */
internal fun fillTargetOf(packageName: String, webDomain: String?, appLabel: String?): FillTarget {
    val host = webDomain?.trim()?.takeIf { it.isNotEmpty() }
    return if (host != null) {
        FillTarget.Web(host, packageName)
    } else {
        FillTarget.App(packageName, appLabel?.trim()?.takeIf { it.isNotEmpty() })
    }
}

/** Decides whether a request gets the suggestion and builds the [FillPlan]. Pure Kotlin. */
internal object FormAnalyzer {

    /**
     * Returns null when the request comes from [ownPackage] (our own screens
     * never get suggestions) or when no visible login field exists.
     */
    fun <Id : Any> analyze(structure: FormStructure<Id>, focusedId: Id?, ownPackage: String): FillPlan<Id>? {
        val packageName = structure.packageName
        if (packageName.isEmpty() || packageName == ownPackage) return null
        val fields = StructureParser.parse(structure, focusedId)
        val login = LoginFieldDetector.find(fields.map { it.info }) ?: return null

        var username = login.username?.let { fields[it] }
        var password = login.password?.let { fields[it] }
        // Never fill one entry into two different web origins (e.g. a form in a
        // frame of another site): keep the field on the focused field's page.
        if (username != null && password != null && !sameDomain(username.info, password.info)) {
            val focused = fields.firstOrNull { it.info.isFocused }?.info
            if (focused != null && sameDomain(focused, username.info)) password = null else username = null
        }
        val anchor = password ?: username ?: return null
        return FillPlan(
            packageName = packageName,
            webDomain = anchor.info.webDomain,
            webScheme = anchor.info.webScheme,
            usernameId = username?.autofillId,
            passwordId = password?.autofillId,
            fields = fields,
            login = LoginFields(
                username = username?.let { login.username },
                password = password?.let { login.password },
            ),
        )
    }

    private fun sameDomain(a: FieldInfo, b: FieldInfo): Boolean =
        a.webDomain.equals(b.webDomain, ignoreCase = true)
}
