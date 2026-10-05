package com.asfandroid.core

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 状态栏快捷磁贴：一键启停 ASF。 */
class AsfTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val running = AsfController.isRunning(this)
        if (running) {
            AsfController.stop(this)
        } else {
            AsfController.start(this)
        }
        // 稍后刷新磁贴状态
        android.os.Handler(mainLooper).postDelayed({ updateTile() }, 1500)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = AsfController.isRunning(this)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (running) "ASF 运行中" else "ASF 未运行"
        tile.updateTile()
    }
}