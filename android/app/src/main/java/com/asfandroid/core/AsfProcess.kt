package com.asfandroid.core

import android.content.Context
import android.util.Log
import com.asfandroid.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 构造并启动 proot + ASF 进程。
 *
 * 命令结构（在 App 私有目录中模拟 Linux 用户态）：
 * <nativeLibDir>/proot -r <rootfs> \
 *   -b <dataDir>:/asf-data -b <dataDir>/resolv.conf:/etc/resolv.conf \
 *   -b <dataDir>/hosts:/etc/hosts \
 *   /bin/sh -c 'cd /asf && exec ./ArchiSteamFarm --no-banner --process-required --path /asf-data'
 *
 * 注意：不绑定 Android 的 /dev、/proc、/sys（会导致 proot 挂起后静默退出），
 * 不使用 --link2symlink（rootfs 已无符号链接）。
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
            // 当 proot 退出时一并终止客体内的所有进程，避免 ASF 变成孤儿进程
            "--kill-on-exit",
            "-r", rootfsDir,
            "-b", "$dataDir:$guestData",
            "-b", "$dataDir/resolv.conf:/etc/resolv.conf",
            "-b", "$dataDir/hosts:/etc/hosts",
            // .NET 运行时依赖 /proc（meminfo、self/maps 等），必须绑定；
            // 不绑定 /dev 和 /sys 避免 proot 扫描大目录挂起
            "-b", "/proc:/proc",
            "-b", "/dev/urandom:/dev/urandom",
            "-b", "/dev/null:/dev/null",
            "/bin/sh", "-c",
            // 环境变量在宿主侧和客体内都设置一份，确保 .NET 运行时一定读到
            "export DOTNET_gcServer=0 DOTNET_GCHeapHardLimit=0x1C0000000 DOTNET_EnableWriteXorExecute=0 && " +
                "cd $guestAsf && exec ./${BuildConfig.ASF_BINARY} --path $guestData"
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
            // TMPDIR 必须是客体内可见的路径（rootfs 的 /tmp），不能用宿主路径，
            // 否则 .NET 单文件运行时解压原生库到不存在的目录导致 CoreCLR 创建失败
            "TMPDIR" to "/tmp",
            "HOME" to "/asf-data",
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
            "SSL_CERT_DIR" to "/etc/ssl/certs",
            "PROOT_LOADER" to loader.absolutePath,
            // Android 内核启用 seccomp，关闭 proot 自身的 seccomp 过滤以兼容
            "PROOT_NO_SECCOMP" to "1",
            // .NET Server GC 会在 Android 内核下预留 256GB 虚拟内存导致 mmap 被拒绝，
            // CoreCLR 报 0x8007000E；强制使用 Workstation GC 并限制堆上限
            "DOTNET_gcServer" to "0",
            // .NET 的 GCHeapHardLimit 要求十六进制字节数
            "DOTNET_GCHeapHardLimit" to "0x1C0000000",
            // 部分 Android 设备 SELinux 禁止 W^X 内存，禁用可避免 JIT 可执行内存分配失败
            "DOTNET_EnableWriteXorExecute" to "0",
            // 让 Android linker 能在 nativeLibraryDir 中找到 libtalloc/libandroid-shmem
            "LD_LIBRARY_PATH" to nativeLibDir
        )
    }

    fun launch(context: Context): Process {
        logDiagnostics(context)
        val builder = ProcessBuilder(buildCommand(context))
        builder.redirectErrorStream(true)
        builder.environment().putAll(prepareEnvironment(context))
        Log.i(TAG, "启动命令: ${builder.command().joinToString(" ")}")
        return builder.start()
    }

    /** 清理客体内残留的 ASF 进程（例如诊断进程被强杀后留下的孤儿）。 */
    fun cleanupOrphans(context: Context) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val rootfsDir = AsfPaths.rootfsDir(context).absolutePath
        val proot = File(nativeLibDir, BuildConfig.PROOT_BINARY).absolutePath
        // 用 [A]rchiSteamFarm 避免 pkill 匹配到自身命令行
        val cmd = listOf(
            proot,
            "-r", rootfsDir,
            "-b", "/proc:/proc",
            "/bin/sh", "-c",
            "pkill -f '[A]rchiSteamFarm' 2>/dev/null; pkill -f '[d]otnet' 2>/dev/null; true"
        )
        runCatching {
            val p = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .apply { environment().putAll(prepareEnvironment(context)) }
                .start()
            if (p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
    }

    /** 输出关键路径状态，便于真机排障（logcat 中过滤 AsfProcess）。 */
    private fun logDiagnostics(context: Context) {
        runCatching {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val proot = File(nativeLibDir, BuildConfig.PROOT_BINARY)
            val loader = File(nativeLibDir, "libproot_loader.so")
            val rootfsDir = AsfPaths.rootfsDir(context)
            val asfBin = File(rootfsDir, "asf/ArchiSteamFarm")
            val sh = File(rootfsDir, "bin/sh")
            Log.i(TAG, "nativeLibDir=$nativeLibDir")
            Log.i(TAG, "proot exists=${proot.exists()} exec=${proot.canExecute()}")
            Log.i(TAG, "loader exists=${loader.exists()} exec=${loader.canExecute()}")
            Log.i(TAG, "rootfsDir=${rootfsDir.absolutePath} isDir=${rootfsDir.isDirectory}")
            Log.i(TAG, "asfBin exists=${asfBin.exists()} exec=${asfBin.canExecute()}")
            Log.i(TAG, "/bin/sh exists=${sh.exists()} (or usr/bin/sh)")
            Log.i(TAG, "dataDir=${AsfPaths.dataDir(context).absolutePath} isDir=${AsfPaths.dataDir(context).isDirectory}")
        }
    }
}