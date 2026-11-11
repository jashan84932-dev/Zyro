package com.zyro.app

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A "Zyro" tile for the quick settings panel: one tap to start or stop listening. */
class ZyroTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            if (Assistant.get(this).active) ListenService.stop(this) else ListenService.start(this)
            qsTile?.state = if (Assistant.get(this).active) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            qsTile?.updateTile()
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.label = "Zyro"
        t.icon = Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        t.state = if (Assistant.get(this).active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
