package com.asfandroid.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启（需用户在系统设置中允许应用自启动）。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("asf_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("boot_autostart", false)) {
                AsfController.start(context)
            }
        }
    }
}