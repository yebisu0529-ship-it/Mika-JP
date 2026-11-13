package com.wangzan101.mika.quit

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.shell.HelperRestart

/** フロートの長押しから、停止の影響を確認する。 */
class CompleteStopActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MikaQuit.blocksWork(this)) {
            finish()
            return
        }
        val app = application as MikaApp
        val body = getString(R.string.quit_body)
        val message = if (app.phone.shellControl.restart == HelperRestart.UsbCommand) {
            "$body\n\n${getString(R.string.quit_usb)}"
        } else body
        AlertDialog.Builder(this)
            .setTitle(R.string.quit_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.quit_action) { _, _ -> MikaQuit.request(app) }
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        fun open(context: Context) {
            if (MikaQuit.blocksWork(context)) return
            context.startActivity(Intent(context, CompleteStopActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
