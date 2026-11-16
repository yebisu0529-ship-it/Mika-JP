package com.wangzan101.mika.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons

/** 任意の権限。許可しない場合は従来のユーザー補助オーバーレイを維持する。 */
@Composable
internal fun CapsuleOverlayPermissionRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        granted = Settings.canDrawOverlays(context)
    }
    ListRow(
        MikaIcons.Message,
        "フローティング表示の互換性",
        if (granted) "通常のフローティングレイヤーを使用します。Aqua Voiceなどをユーザー補助レイヤーで覆いません。"
        else "Aqua Voiceなどより前に出る場合は「他のアプリの上に表示」を許可してください。未許可の場合は従来の表示を使います。",
        onClick = {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
            if (intent.resolveActivity(context.packageManager) != null) launcher.launch(intent)
        },
    ) { RowChevron() }
}
