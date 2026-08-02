package io.nekohasekai.sfa.bg

import android.app.KeyguardManager
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.nekohasekai.sfa.constant.Status

@RequiresApi(24)
class TileService :
    TileService(),
    ServiceConnection.Callback {
    private val connection = ServiceConnection(this, this)

    override fun onServiceStatusChanged(status: Status) {
        qsTile?.apply {
            state =
                when (status) {
                    // Transitional states count as active rather than unavailable: the system
                    // swallows clicks on an unavailable tile, and this tile is the fallback way
                    // to stop a service whose own UI has gone quiet.
                    Status.Started, Status.Starting, Status.Stopping -> Tile.STATE_ACTIVE
                    Status.Stopped -> Tile.STATE_INACTIVE
                }
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect()
    }

    override fun onStopListening() {
        connection.disconnect()
        super.onStopListening()
    }

    override fun onClick() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            unlockAndRun {
                toggleService()
            }
        } else {
            toggleService()
        }
    }

    private fun toggleService() {
        when (connection.status) {
            Status.Stopped -> BoxService.start()
            // A transitional state stops too, so the tile stays a way out when the app's own
            // button is out of reach. The service treats a repeat as "force it".
            Status.Started, Status.Starting, Status.Stopping -> BoxService.stop()
        }
    }
}
