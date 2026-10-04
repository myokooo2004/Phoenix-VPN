package com.phoenix.phoenixvpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phoenix.phoenixvpn.ui.HomeScreen
import com.phoenix.phoenixvpn.ui.VpnViewModel
import com.phoenix.phoenixvpn.ui.theme.PhoenixVpnTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: VpnViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
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

        setContent {
            PhoenixVpnTheme {
                HomeScreen(viewModel = viewModel)
            }
        }
    }
}
