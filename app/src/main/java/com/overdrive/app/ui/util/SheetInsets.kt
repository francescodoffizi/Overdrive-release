package com.overdrive.app.ui.util

import android.view.View

/**
 * M3 BottomSheetDialog (ThemeOverlay.Material3.BottomSheetDialog) enables
 * edge-to-edge by default, laying the sheet content out BEHIND the system
 * navigation bar — on the head unit this hides the bottom of the sheet
 * (e.g. the recordings filter sheet's Apply button) under the native bar.
 *
 * [padForNavigationBar] pads the sheet root by the navigation-bar inset so
 * the last control always clears the bar. Idempotent: sets absolute padding
 * from the current inset instead of accumulating across dispatches. If the
 * window never dispatches insets (non-edge-to-edge devices/themes) the
 * listener simply never fires and the sheet renders exactly as before.
 */
object SheetInsets {

    @JvmStatic
    fun padForNavigationBar(root: View) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nav = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            ).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav)
            insets
        }
    }
}
