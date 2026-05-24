package com.example.tempoz

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
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
import com.example.tempoz.ui.theme.TempozTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TempozTheme {
                val context = LocalContext.current

                val permission = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                val onFileSelected: OnFileSelected = { pfd, _, _, _ ->
                    // TODO: replaced by ViewModel wiring in task 5
                    pfd.close()
                }

                val fileLauncher = rememberLauncherForActivityResult(GetContent()) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: return@rememberLauncherForActivityResult
                    var length = pfd.statSize
                    var displayName: String? = null
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            displayName = cursor.getString(0)
                            if (length <= 0L) {
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                                    length = cursor.getLong(sizeIndex)
                                }
                            }
                        }
                    }
                    if (length <= 0L) {
                        pfd.close()
                        return@rememberLauncherForActivityResult
                    }
                    onFileSelected(pfd, 0L, length, displayName ?: uri.lastPathSegment ?: "Unknown")
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

/**
 * Callback invoked when the user selects an audio file.
 *
 * Ownership of [pfd] transfers to the callback. The callback is responsible for closing it after
 * use (e.g. after passing [pfd.fd][android.os.ParcelFileDescriptor.getFd] to the audio engine).
 *
 * @param pfd open [ParcelFileDescriptor] for the selected file; caller must close it
 * @param offset byte offset within the file (always 0 for a direct content URI)
 * @param length total byte length of the file; sourced from [android.os.ParcelFileDescriptor.getStatSize]
 *   with [OpenableColumns.SIZE] as a fallback; guaranteed to be positive (callback is not invoked otherwise)
 * @param displayName human-readable file name from [OpenableColumns.DISPLAY_NAME]
 */
typealias OnFileSelected = (pfd: ParcelFileDescriptor, offset: Long, length: Long, displayName: String) -> Unit

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
