package io.github.krax1337.gsync.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.krax1337.gsync.GsyncApp
import io.github.krax1337.gsync.sync.Permissions
import io.github.krax1337.gsync.sync.SyncService
import io.github.krax1337.gsync.watch.WatchManager

class MainActivity : ComponentActivity() {
    private val manager: WatchManager
        get() = (application as GsyncApp).manager

    private var permissionsGranted by mutableStateOf(false)

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsGranted = Permissions.granted(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by manager.state.collectAsStateWithLifecycle()
            val paired = state.watch != null
            LaunchedEffect(paired, permissionsGranted) {
                if (paired && permissionsGranted) SyncService.start(this@MainActivity)
            }
            GsyncTheme {
                GsyncUi(
                    state = state,
                    actions = manager,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { permissionRequest.launch(Permissions.REQUIRED) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The user may have changed permissions in system settings while the app was away.
        permissionsGranted = Permissions.granted(this)
        manager.setUiVisible(true)
    }

    override fun onStop() {
        manager.setUiVisible(false)
        super.onStop()
    }
}
