package com.tonbiattack.instantmic

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings tile that opens the app ready to talk.
 *
 * The tile never records audio itself: Android only allows microphone capture
 * from a foreground activity, and keeping the background-listening restriction
 * is deliberate. Tapping the tile brings the app forward, and the activity
 * starts listening immediately (see [MainActivity]).
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_START_LISTENING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            // Pre-14 has no PendingIntent overload; this is the only API there.
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_mic)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.tile_subtitle)
        }
        tile.updateTile()
    }
}
