package com.asfandroid.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.asfandroid.AsfApp
import com.asfandroid.MainActivity
import com.asfandroid.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在独立进程 (:asf) 中运行 ASF 的前台服务。
 * 生命周期：启动 -> 解压 rootfs -> 写入配置 -> 拉起 proot+ASF -> 常驻。
 */
class AsfService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAsf()
                return START_NOT_STICKY
            }
            else -> {
                if (!started) {
                    started = true
                    startAsfAsync()
                }
                return START_STICKY
            }
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification("ASF 正在启动…")
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, AsfApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_asf)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startAsfAsync() {
        scope.launch {
            try {
                val rootfsDir = AsfPaths.rootfsDir(this@AsfService)
                if (!rootfsDir.isDirectory || !File(rootfsDir, "asf/ArchiSteamFarm").exists()) {
                    ensureRootfs(rootfsDir)
                }
                AsfConfig.ensureDefaults(AsfPaths.configDir(this@AsfService))
                AsfProcess.ensureNetworkFiles(this@AsfService)
                launchProcess()
                // 进程退出后，结束服务
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "ASF 启动失败", e)
                updateNotification("ASF 启动失败")
                stopSelf()
            }
        }
    }

    private fun ensureRootfs(rootfsDir: File) {
        val assetPath = AsfPaths.rootfsTarAsset()
        try {
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            AssetUtils.extractTar(this, assetPath, rootfsDir)
        } catch (e: Exception) {
            throw IllegalStateException(
                "无法解压 rootfs（$assetPath）。请确认已运行 scripts/build-rootfs.sh 与 prepare-assets.sh。",
                e
            )
        }
    }

    private fun launchProcess() {
        if (process?.isAlive == true) return
        val p = AsfProcess.launch(this)
        process = p
        updateNotification("ASF 运行中")
        // 把 stdout/stderr 重定向到日志文件；进程退出后此循环返回
        p.inputStream.use { input ->
            val logFile = File(AsfPaths.logsDir(this), "asf-console.log").apply { parentFile?.mkdirs() }
            input.copyTo(logFile.outputStream().buffered())
        }
    }

    private fun stopAsf() {
        scope.launch {
            process?.let { p ->
                p.destroy()
                try {
                    p.waitFor(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (p.isAlive) p.destroyForcibly()
            }
            process = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // 结束本进程，确保 .NET 线程全部退出
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        process?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AsfService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.asfandroid.action.START"
        const val ACTION_STOP = "com.asfandroid.action.STOP"
    }
}