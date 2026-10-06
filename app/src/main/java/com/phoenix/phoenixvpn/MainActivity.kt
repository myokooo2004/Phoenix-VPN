package com.phoenix.phoenixvpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phoenix.phoenixvpn.analytics.Analytics
import com.phoenix.phoenixvpn.ui.HomeScreen
import com.phoenix.phoenixvpn.ui.VpnViewModel
import com.phoenix.phoenixvpn.ui.theme.PhoenixVpnTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: VpnViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    /**
     * Battery-exemption screen. The exemption setting can take a moment to
     * propagate after the user grants it, so we wait ~1s before telling the
     * strip to re-check — this beats the ON_RESUME race the old flow had.
     */
    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        lifecycleScope.launch {
            delay(1000)
            viewModel.onBatteryExemptionReturned()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Analytics.init(this)
        checkNotificationPermission()

        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

        lifecycleScope.launch {
            viewModel.permissionRequest.collect { intent: Intent? ->
                if (intent != null) {
                    try {
                        vpnPermissionLauncher.launch(intent)
                    } catch (_: Exception) {
                        viewModel.onVpnPermissionResult(false)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.batteryExemptionRequest.collect {
                try {
                    batteryExemptionLauncher.launch(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    // Fall through: the ON_RESUME check in the strip remains.
                }
            }
        }

        setContent {
            PhoenixVpnTheme {
                HomeScreen(viewModel = viewModel)
            }
        }
    }
}
