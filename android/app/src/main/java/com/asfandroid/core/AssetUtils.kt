package com.asfandroid.core

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

/**
 * 从 assets 中解压 tar（自动识别 gzip 压缩）到目标目录，保留符号链接与可执行权限。
 */
object AssetUtils {

    private const val TAG = "AssetUtils"

    private const val MODE_EXEC_MASK = 0x49 // owner/group/other execute bits

    fun extractTar(context: Context, assetPath: String, destDir: File) {
        destDir.mkdirs()
        val destRoot = destDir.canonicalPath

        context.assets.open(assetPath).use { raw ->
            val buffered = BufferedInputStream(raw)
            buffered.mark(2)
            val magic = ByteArray(2)
            val read = buffered.read(magic)
            buffered.reset()
            val isGzip = read == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()

            val tarInput: TarArchiveInputStream = if (isGzip) {
                TarArchiveInputStream(GZIPInputStream(buffered))
            } else {
                TarArchiveInputStream(buffered)
            }

            tarInput.use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
                while (entry != null) {
                    extractEntry(tar, entry, destDir, destRoot)
                    entry = tar.nextTarEntry
                }
            }
        }
        Log.i(TAG, "rootfs 解压完成: $destDir")
    }

    private fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, destDir: File, destRoot: String) {
        val target = File(destDir, entry.name).normalize()
        if (!target.canonicalPath.startsWith(destRoot)) {
            throw IOException("非法解压路径: ${entry.name}")
        }

        when {
            entry.isDirectory -> {
                target.mkdirs()
                applyPermissions(target.toPath(), entry.mode)
            }

            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                target.delete()
                try {
                    Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
                } catch (e: Exception) {
                    Log.w(TAG, "无法创建符号链接 ${entry.name}: ${e.message}")
                }
            }

            // tar --dereference 会把符号链接变成硬链接：复制目标文件内容
            entry.isLink -> {
                val linkTarget = File(destDir, entry.linkName).normalize()
                if (linkTarget.exists()) {
                    target.parentFile?.mkdirs()
                    linkTarget.copyTo(target, overwrite = true)
                    applyPermissions(target.toPath(), entry.mode)
                } else {
                    Log.w(TAG, "硬链接目标不存在: ${entry.linkName}")
                }
            }

            else -> {
                target.parentFile?.mkdirs()
                tar.copyTo(
                    Files.newOutputStream(
                        target.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                    )
                )
                applyPermissions(target.toPath(), entry.mode)
            }
        }
    }

    private fun applyPermissions(path: Path, mode: Int) {
        val file = path.toFile()
        // 只要 owner/group/other 任一对应权限位有效，就开放该权限
        file.setReadable((mode and 0x124) != 0, false)
        file.setWritable((mode and 0x92) != 0, false)
        file.setExecutable((mode and MODE_EXEC_MASK) != 0, false)
    }
}