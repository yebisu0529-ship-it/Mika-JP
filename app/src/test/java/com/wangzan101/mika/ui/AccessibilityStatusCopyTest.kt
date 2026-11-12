package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccessibilityStatusCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun restrictedDisabledAccessibilityExplainsTheAppInfoRoute() {
        val problem = Problem.Accessibility(AccessibilityState.Off, restrictedSettings = true)
        assertTrue(StatusCopy.banner(problem).detail.contains("制限された設定を許可"))
        val explanation = StatusCopy.explain(problem, "14")
        assertTrue(explanation.body.contains("アプリ情報"))
        assertTrue(explanation.body.contains("があれば選んで"))
        assertFalse(explanation.body.contains("一度オフ"))
        assertEquals(Fix.OpenAccessibility, explanation.fix)
        assertEquals(Fix.OpenAppInfo, explanation.secondaryAction?.second)
    }

    @Test fun unrestrictedDisabledAccessibilityOpensAccessibilitySettings() {
        val problem = Problem.Accessibility(AccessibilityState.Off)
        assertFalse(StatusCopy.banner(problem).detail.contains("制限された設定を許可"))
        val explanation = StatusCopy.explain(problem, "14")
        assertEquals(Fix.OpenAccessibility, explanation.fix)
        assertNull(explanation.secondaryAction)
    }
}
