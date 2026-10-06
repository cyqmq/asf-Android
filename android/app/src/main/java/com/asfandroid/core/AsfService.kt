package com.asfandroid.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.asfandroid.AsfApp
import com.asfandroid.BuildConfig
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
        AsfController.setServiceAlive(this, true)
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
                writePhase("service_started")
                updateNotification("正在解压 rootfs（首次约 1-3 分钟）…")
                val rootfsDir = AsfPaths.rootfsDir(this@AsfService)
                if (!rootfsDir.isDirectory || !File(rootfsDir, "asf/ArchiSteamFarm").exists()) {
                    writePhase("extracting_rootfs")
                    ensureRootfs(rootfsDir)
                    writePhase("rootfs_extracted")
                }
                AsfConfig.ensureDefaults(AsfPaths.configDir(this@AsfService))
                AsfProcess.ensureNetworkFiles(this@AsfService)
                writePhase("launching_process")
                runProotDiagnostics()
                launchProcess()
                // 进程退出后，结束服务
                writePhase("process_exited")
                stopSelf()
            } catch (e: java.io.InterruptedIOException) {
                // 读取进程输出被中断，通常是用户停止服务/进程被销毁，属正常现象
                Log.i(TAG, "启动过程被中断（服务停止）: ${e.message}")
                writePhase("start_interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "ASF 启动失败", e)
                writeStartupError(e)
                updateNotification("ASF 启动失败，请查看日志")
                stopSelf()
            }
        }
        // 看门狗：长时间未就绪则写入诊断
        scope.launch {
            kotlinx.coroutines.delay(180_000)
            if (!AsfController.isRunning(this@AsfService)) {
                writePhase("not_ready_after_180s")
                writeWarning("ASF 在 180 秒内未就绪。请查看 asf-console.log / startup-error.log")
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
        // 清理上次残留的孤儿 ASF（例如旧版本强杀诊断留下的）
        AsfProcess.cleanupOrphans(this)
        AsfController.setIpcReady(this, false)
        val p = AsfProcess.launch(this)
        process = p
        writePhase("process_launched")
        updateNotification("ASF 运行中")
        // 把 stdout/stderr 逐行实时写入日志文件；进程退出后此循环返回
        p.inputStream.bufferedReader().useLines { lines ->
            val logFile = File(AsfPaths.logsDir(this), "asf-console.log").apply { parentFile?.mkdirs() }
            logFile.outputStream().bufferedWriter().use { writer ->
                lines.forEach { line ->
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                    // 从 ASF 日志中识别 IPC 就绪信号，写入状态供主进程读取
                    if (line.contains("Now listening on") || line.contains("IPC server ready")) {
                        AsfController.setIpcReady(this, true)
                    }
                }
            }
        }
        // 进程退出
        AsfController.setIpcReady(this, false)
        runCatching {
            p.waitFor(2, TimeUnit.SECONDS)
            writePhase("process_exited exitCode=${p.exitValue()}")
        }
    }

    /**
 * 分级 proot 诊断：从最简命令到完整命令逐步测试，
 * 记录退出码与输出到 logs/proot-diag.log，用于定位挂起/静默退出。
 */
    private fun runProotDiagnostics() {
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val proot = File(nativeLibDir, BuildConfig.PROOT_BINARY).absolutePath
        val rootfs = AsfPaths.rootfsDir(this).absolutePath
        val kernelRelease = System.getProperty("os.version").orEmpty()
        val diagFile = File(AsfPaths.logsDir(this), "proot-diag.log").apply { parentFile?.mkdirs() }
        diagFile.writeText("kernelRelease=$kernelRelease\nrootfs=$rootfs\n\n")

        fun runTest(name: String, args: List<String>) {
            val sb = StringBuilder("##### $name #####\n")
            try {
                val cmd = listOf(proot) + args
                val p = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .apply { environment().putAll(AsfProcess.prepareEnvironment(this@AsfService)) }
                    .start()
                val output = StringBuilder()
                val reader = Thread {
                    runCatching { output.append(p.inputStream.bufferedReader().readText()) }
                }.apply { isDaemon = true; start() }
                val finished = p.waitFor(15, TimeUnit.SECONDS)
                if (!finished) p.destroyForcibly()
                reader.join(2000)
                val exit = runCatching { p.exitValue() }.getOrDefault(-999)
                sb.append("exit=$exit\n$output\n")
            } catch (e: Exception) {
                sb.append("exception=$e\n")
            }
            diagFile.appendText(sb.toString())
        }

        runTest("A: bare -r", listOf("-r", rootfs, "/bin/sh", "-c", "echo HELLO_A"))
        runTest("B: +link2symlink", listOf("--link2symlink", "-r", rootfs, "/bin/sh", "-c", "echo HELLO_B"))
        runTest("C: +kernel-release", listOf("--link2symlink", "--kernel-release=$kernelRelease", "-r", rootfs, "/bin/sh", "-c", "echo HELLO_C"))
        // 注意：不再运行 D（完整 ASF）测试，因为强杀 proot 会在客体内留下孤儿 ASF 进程，
        // 导致后续真正的启动报 "already running"。
        // 验证 .NET 相关环境变量是否传递到客体内
        runTest("E: env check", listOf("-r", rootfs, "/bin/sh", "-c", "echo gcServer=\$DOTNET_gcServer heap=\$DOTNET_GCHeapHardLimit wxor=\$DOTNET_EnableWriteXorExecute tmp=\$TMPDIR"))
        // 查看客体内的虚拟内存限制与 overcommit 策略，用于定位 mmap 失败
        runTest("F: memory env", listOf("-b", "/proc:/proc", "-r", rootfs, "/bin/sh", "-c", "ulimit -v; { read -r oc; } < /proc/sys/vm/overcommit_memory 2>/dev/null; echo overcommit=\$oc; { read -r mt; } < /proc/meminfo 2>/dev/null; echo meminfo=\$mt"))
    }

    private fun writePhase(phase: String) {
        runCatching {
            val f = File(AsfPaths.logsDir(this), "startup-phase.log")
            f.parentFile?.mkdirs()
            f.appendText("%tF %tT  %s%n".format(java.util.Date(), java.util.Date(), phase))
        }
    }

    private fun writeWarning(msg: String) {
        runCatching {
            val f = File(AsfPaths.logsDir(this), "startup-warning.log")
            f.parentFile?.mkdirs()
            f.appendText("%tF %tT  %s%n".format(java.util.Date(), java.util.Date(), msg))
        }
    }

    private fun writeStartupError(e: Exception) {
        runCatching {
            val f = File(AsfPaths.logsDir(this), "startup-error.log")
            f.parentFile?.mkdirs()
            f.writeText(Log.getStackTraceString(e))
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
            // 兜底清理客体内可能残留的 ASF 进程
            AsfProcess.cleanupOrphans(this@AsfService)
            AsfController.setIpcReady(this@AsfService, false)
            AsfController.setServiceAlive(this@AsfService, false)
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
        AsfController.setServiceAlive(this, false)
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