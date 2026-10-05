package com.asfandroid.core

import android.content.Context
import com.asfandroid.BuildConfig
import java.io.File

object AsfPaths {

    fun rootfsDir(context: Context): File = File(context.filesDir, "linux")

    fun dataDir(context: Context): File = File(context.filesDir, "asf-data")

    fun configDir(context: Context): File = File(dataDir(context), "config")

    fun logsDir(context: Context): File = File(dataDir(context), "logs")

    fun prootTmpDir(context: Context): File = File(context.filesDir, "proot-tmp")

    fun rootfsTarAsset(): String = BuildConfig.ROOTFS_ASSET

    fun asfUrl(): String = BuildConfig.ASF_IPC_URL
}