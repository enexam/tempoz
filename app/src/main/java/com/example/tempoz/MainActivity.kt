package com.example.tempoz

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tempoz.ui.PlayerScreen
import com.example.tempoz.ui.theme.TempozTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(RequestPermission()) { /* no-op: best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            TempozTheme {
                val context = LocalContext.current
                val viewModel: PlaybackViewModel = viewModel()

                val permission = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                val fileLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    // Persist read access so the track can be reopened after an app
                    // restart/reinstall. ACTION_OPEN_DOCUMENT (unlike GET_CONTENT)
                    // returns a URI whose grant is persistable.
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    viewModel.selectFile(context, uri)
                }

                val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
                    if (granted) {
                        fileLauncher.launch(arrayOf("audio/*"))
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PlayerScreen(
                        viewModel = viewModel,
                        onPickFile = { permissionLauncher.launch(permission) },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
