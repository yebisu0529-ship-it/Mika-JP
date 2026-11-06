package com.wangzan101.mika.cap.phone.shell

import android.content.pm.ActivityInfo

/** 背景画面がメイン画面のフォーカスを奪わない場合、システムは水平画面のアクティビティを垂直画面の上部に縮小することがあります。システムによって報告されたアプリケーション ウィンドウの比率に従って、画面の向きを修正します。 */
internal object DisplayOrientation {
    fun rotation(
        bufferWidth: Int,
        bufferHeight: Int,
        current: Int,
        appWidth: Int,
        appHeight: Int,
        requestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
    ): Int? {
        if (bufferWidth == bufferHeight) return null
        val appLandscape = when {
            appWidth > 0 && appHeight > 0 && appWidth != appHeight -> appWidth > appHeight
            else -> requestedLandscape(requestedOrientation) ?: return null
        }
        val naturalLandscape = bufferWidth > bufferHeight
        val currentLandscape = if (current % 2 == 0) naturalLandscape else !naturalLandscape
        if (currentLandscape == appLandscape) return null
        return if (naturalLandscape == appLandscape) 0 else 1
    }

    private fun requestedLandscape(orientation: Int): Boolean? = when (orientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> false
        else -> null
    }
}
