package com.asfandroid.core

import android.content.Context
import android.util.Log
import com.asfandroid.BuildConfig
import java.io.File

/**
 * 构造并启动 proot + ASF 进程。
 *
 * 命令结构（在 App 私有目录中模拟 Linux 用户态）：
 * <nativeLibDir>/proot -0 -r <rootfs> -b <dataDir>:/asf-data \
 *   -b /dev:/dev -b /proc:/proc -b /sys:/sys -w /asf \
 *   /bin/sh -c 'cd /asf && exec ./ArchiSteamFarm --no-banner --process-required --path /asf-data'
 */
object AsfProcess {

    private const val TAG = "AsfProcess"

    fun buildCommand(context: Context): List<String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val rootfsDir = AsfPaths.rootfsDir(context).absolutePath
        val dataDir = AsfPaths.dataDir(context).absolutePath

        val proot = File(nativeLibDir, BuildConfig.PROOT_BINARY).absolutePath
        val guestData = "/asf-data"
        val guestAsf = "/asf"

        return listOf(
            proot,
            "-0",
            "-r", rootfsDir,
            "-b", "$dataDir:$guestData",
            "-b", "$dataDir/resolv.conf:/etc/resolv.conf",
            "-b", "$dataDir/hosts:/etc/hosts",
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            "-b", "/dev/urandom:/dev/urandom",
            "-w", guestAsf,
            "/bin/sh", "-c",
            "cd $guestAsf && exec ./${BuildConfig.ASF_BINARY} --no-banner --process-required --path $guestData"
        )
    }

    /** 写入 proot 客体内使用的网络基础文件（DNS/hosts）。 */
    fun ensureNetworkFiles(context: Context) {
        val dataDir = AsfPaths.dataDir(context)
        dataDir.mkdirs()
        val resolv = File(dataDir, "resolv.conf")
        if (!resolv.exists()) {
            resolv.writeText(
                """
                nameserver 223.5.5.5
                nameserver 119.29.29.29
                nameserver 8.8.8.8
                nameserver 1.1.1.1
                """.trimIndent() + "\n"
            )
        }
        val hosts = File(dataDir, "hosts")
        if (!hosts.exists()) {
            hosts.writeText(
                """
                127.0.0.1 localhost
                ::1 localhost ip6-localhost
                """.trimIndent() + "\n"
            )
        }
    }

    fun prepareEnvironment(context: Context): Map<String, String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val tmpDir = AsfPaths.prootTmpDir(context)
        tmpDir.mkdirs()
        // proot 默认会把内置 loader 解压到 TMPDIR 再执行；直接指定 jniLibs 里的 loader
        // 可避免 App 数据目录 noexec 导致 loader 无法执行的问题。
        val loader = File(nativeLibDir, "libproot_loader.so")
        return mapOf(
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "HOME" to AsfPaths.dataDir(context).absolutePath,
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
            "SSL_CERT_DIR" to "/etc/ssl/certs",
            "PROOT_LOADER" to loader.absolutePath,
            // 让 Android linker 能在 nativeLibraryDir 中找到 libtalloc/libandroid-shmem
            "LD_LIBRARY_PATH" to nativeLibDir
        )
    }

    fun launch(context: Context): Process {
        val builder = ProcessBuilder(buildCommand(context))
        builder.redirectErrorStream(true)
        builder.environment().putAll(prepareEnvironment(context))
        Log.i(TAG, "启动命令: ${builder.command().joinToString(" ")}")
        return builder.start()
    }
}