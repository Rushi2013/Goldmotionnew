package ai.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import ai.jarvis.ui.nav.JarvisRoot
import ai.jarvis.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private var pendingRoute by mutableStateOf<String?>(null)
    private var pendingPermission by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult?.invoke(granted)
        onPermissionResult = null
    }

    companion object {
        @Volatile var onPermissionResult: ((Boolean) -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute = intent?.getStringExtra("route")
        requestNotificationPermissionIfNeeded()

        setContent {
            JarvisTheme {
                JarvisRoot(
                    initialRoute = pendingRoute,
                    requestPermission = { permission ->
                        pendingPermission = permission
                        val key = if (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) {
                            null
                        } else permission
                        if (key != null && ContextCompat.checkSelfPermission(this, key) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(key)
                        } else {
                            onPermissionResult?.invoke(true)
                            onPermissionResult = null
                        }
                    },
                    hasPermission = { permission ->
                        if (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) true
                        else ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra("route")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}
