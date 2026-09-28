package sh.gerra.again

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.gerra.again.platform.AndroidCamera
import sh.gerra.again.platform.AndroidPhotos

class MainActivity : ComponentActivity() {
    // Both register their activity-result launchers, which has to happen before onCreate returns.
    private val photos = AndroidPhotos(this)
    private val camera = AndroidCamera(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Drawn edge to edge under transparent bars, with light icons on the app's dark background.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // A fresh start: new photos from last time that were not saved are of no more use.
        if (savedInstanceState == null) photos.clearCaptures()
        camera.refreshPermission()
        setContent {
            // The system back button / predictive back gesture pops the navigator while it has
            // somewhere to go; at Home the callback is disabled so the system leaves the app.
            App(photos, camera, systemBack = { enabled, onBack -> BackHandler(enabled, onBack) })
        }
    }

    /** The camera may have been allowed in Settings while the app was in the background. */
    override fun onResume() {
        super.onResume()
        camera.refreshPermission()
    }
}
