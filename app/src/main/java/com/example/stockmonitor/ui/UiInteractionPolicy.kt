package com.example.stockmonitor.ui

object UiInteractionPolicy {

    fun shouldDeferRender(textInputFocused: Boolean, imeVisible: Boolean = false): Boolean =
        textInputFocused || imeVisible

    fun bottomBarInset(systemBarsBottom: Int, imeBottom: Int): Int = systemBarsBottom

    fun isImeVisible(
        insetsVisible: Boolean,
        imeBottom: Int,
        rootHeight: Int,
        visibleFrameBottom: Int,
        threshold: Int,
    ): Boolean = insetsVisible || imeBottom > 0 || rootHeight - visibleFrameBottom > threshold
}
