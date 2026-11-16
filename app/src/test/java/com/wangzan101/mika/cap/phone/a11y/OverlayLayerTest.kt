package com.wangzan101.mika.cap.phone.a11y

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayLayerTest {
    @Test fun ordinaryLayerDoesNotUseAccessibilityOverlay() {
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, overlayWindowType(true))
    }
    @Test fun deniedOptionalPermissionKeepsExistingLayer() {
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlayWindowType(false))
    }
}
