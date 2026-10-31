package de.reitano.keypassmobile.prototype.autofill

/**
 * Fixed test entries for the prototype; the final app reads the KDBX instead.
 * Values are dummies, not real credentials.
 */
object TestEntries {
    val ALL: List<AutofillEntry> = listOf(
        AutofillEntry("Testseite Emulator", "emu-user", "emu-pass-1", "http://10.0.2.2"),
        AutofillEntry("Testseite localhost", "local-user", "local-pass-2", "http://localhost"),
        AutofillEntry("Example", "example-user", "example-pass-3", "https://example.com"),
        AutofillEntry("PayPal", "paypal-user@example.com", "paypal-pass-4", "https://www.paypal.com"),
        AutofillEntry("KPM Prototyp (Testformular)", "app-user", "app-pass-5", null),
    )
}
