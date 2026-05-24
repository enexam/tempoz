package com.example.tempoz

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tempoz.ui.theme.TempozTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

                val fileLauncher = rememberLauncherForActivityResult(GetContent()) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    viewModel.selectFile(context, uri)
                }

                val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
                    if (granted) {
                        fileLauncher.launch("audio/*")
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TempozApp(
                        onPickFile = { permissionLauncher.launch(permission) },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}


@Composable
fun TempozApp(
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    // TODO: replace with PlayerScreen in task 5
    Button(
        onClick = onPickFile,
        modifier = modifier
    ) {
        Text(text = "Pick audio file")
    }
}

@Preview(showBackground = true)
@Composable
fun TempozAppPreview() {
    TempozTheme {
        TempozApp(onPickFile = {})
    }
}
