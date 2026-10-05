package com.asfandroid.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket

object AsfController {

    fun start(context: Context) {
        val intent = Intent(context, AsfService::class.java).setAction(AsfService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, AsfService::class.java).setAction(AsfService.ACTION_STOP))
    }

    /** 尝试连接 ASF IPC 端口，判断是否已就绪。 */
    fun isRunning(context: Context, timeoutMs: Int = 300): Boolean {
        val url = AsfPaths.asfUrl() // http://127.0.0.1:1242
        val port = url.substringAfterLast(':').toIntOrNull() ?: 1242
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}