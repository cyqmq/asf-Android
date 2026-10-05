package com.asfandroid.core

import java.io.File

object AsfConfig {

    /** 写入 ASF 默认配置文件（仅在文件不存在时）。 */
    fun ensureDefaults(configDir: File) {
        configDir.mkdirs()

        val asfJson = File(configDir, "ASF.json")
        if (!asfJson.exists()) {
            asfJson.writeText(
                """
                {
                  "Headless": true,
                  "AutoUpdates": false,
                  "CurrentCulture": "en-US"
                }
                """.trimIndent()
            )
        }

        val ipcConfig = File(configDir, "IPC.config")
        if (!ipcConfig.exists()) {
            ipcConfig.writeText(
                """
                {
                  "Kestrel": {
                    "Endpoints": {
                      "HTTP": {
                        "Url": "http://127.0.0.1:1242"
                      }
                    }
                  }
                }
                """.trimIndent()
            )
        }
    }
}