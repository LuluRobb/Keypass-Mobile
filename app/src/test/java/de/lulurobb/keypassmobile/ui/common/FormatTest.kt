package de.lulurobb.keypassmobile.ui.common

import de.lulurobb.keypassmobile.model.FillTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FormatTest {

    @Test
    fun `app name is the visible label`() {
        assertEquals("PayPal", FillTarget.App("com.paypal.android.p2pmobile", "PayPal").displayName())
    }

    @Test
    fun `app name falls back to the package name without a readable label`() {
        assertEquals("com.example.app", FillTarget.App("com.example.app", null).displayName())
    }
}
