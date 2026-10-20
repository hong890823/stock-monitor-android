package com.example.stockmonitor

import com.example.stockmonitor.ui.UiInteractionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiInteractionPolicyTest {

    @Test
    fun renderingIsDeferredWhileTextInputHasFocus() {
        assertTrue(UiInteractionPolicy.shouldDeferRender(textInputFocused = true))
    }

    @Test
    fun bottomNavigationOnlyConsumesSystemNavigationInset() {
        assertEquals(24, UiInteractionPolicy.bottomBarInset(systemBarsBottom = 24, imeBottom = 1000))
    }

    @Test
    fun keyboardFallbackUsesVisibleWindowHeightWhenInsetsDoNotReportIme() {
        assertTrue(
            UiInteractionPolicy.isImeVisible(
                insetsVisible = false,
                imeBottom = 0,
                rootHeight = 2772,
                visibleFrameBottom = 1305,
                threshold = 240,
            )
        )
    }
}
