package com.asfandroid.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** ASF 运行状态 */
enum class AsfStatus {
    STOPPED,   // 未运行
    STARTING,  // 服务已启动，正在解压 rootfs / 拉起进程
    RUNNING    // ASF IPC 端口已就绪
}

object AsfController {

    private const val PREFS = "asf_status"
    private const val KEY_SERVICE_ALIVE = "service_alive"
    private const val KEY_IPC_READY = "ipc_ready"

    fun start(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_ALIVE, true).apply()
        val intent = Intent(context, AsfService::class.java).setAction(AsfService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_ALIVE, false).apply()
        context.startService(Intent(context, AsfService::class.java).setAction(AsfService.ACTION_STOP))
    }

    /** 由 AsfService 在 onCreate/onDestroy 中维护的存活标记。 */
    fun setServiceAlive(context: Context, alive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_ALIVE, alive).apply()
    }

    /** 服务进程是否真正存活（SharedPreferences 标记 + ActivityManager 双重确认）。 */
    fun isServiceAlive(context: Context): Boolean {
        val prefsAlive = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ALIVE, false)
        if (!prefsAlive) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val running = am.getRunningServices(100).any { it.service.className == AsfService::class.java.name }
        return running
    }

    /** 由服务进程在探测到 ASF IPC 就绪时写入标记文件（跨进程可靠，SharedPreferences 有进程缓存问题）。 */
    fun setIpcReady(context: Context, ready: Boolean) {
        val file = File(context.filesDir, "asf-ipc-ready")
        if (ready) {
            file.writeText(System.currentTimeMillis().toString())
        } else {
            file.delete()
        }
    }

    fun isIpcReady(context: Context): Boolean =
        File(context.filesDir, "asf-ipc-ready").exists()

    /** 尝试连接 ASF IPC 端口，判断是否已就绪。 */
    fun isRunning(context: Context, timeoutMs: Int = 500): Boolean {
        val url = AsfPaths.asfUrl() // http://127.0.0.1:1242
        val port = url.substringAfterLast(':').toIntOrNull() ?: 1242
        val host = url.substringAfter("://").substringBefore(':')
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 综合状态：优先使用服务进程写入的 IPC 就绪标记，其次用 Socket 探测兜底。 */
    fun getStatus(context: Context): AsfStatus = when {
        isIpcReady(context) || isRunning(context) -> AsfStatus.RUNNING
        isServiceAlive(context) -> AsfStatus.STARTING
        else -> AsfStatus.STOPPED
    }
}