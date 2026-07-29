package com.yashasvm.holen

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.toArgb
import com.yashasvm.holen.ui.HolenBackground
import com.yashasvm.holen.ui.HolenScreen
import com.yashasvm.holen.ui.HolenTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var outputStore: OutputStore
    private var queueAfterPermission = false

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            val persisted = runCatching {
                outputStore.persistTree(
                    uri,
                    data.flags,
                )
                check(outputStore.hasValidTreeGrant())
            }
            if (persisted.isSuccess) {
                viewModel.folderSelectionSucceeded()
            } else {
                viewModel.folderSelectionFailed()
            }
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (queueAfterPermission) {
            queueAfterPermission = false
            viewModel.queue()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputStore = OutputStore(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                HolenBackground.toArgb(),
                HolenBackground.toArgb(),
            ),
            navigationBarStyle = SystemBarStyle.light(
                HolenBackground.toArgb(),
                HolenBackground.toArgb(),
            ),
        )
        populateFromIntent(intent)
        setContent {
            HolenTheme {
                HolenScreen(
                    viewModel = viewModel,
                    onChooseFolder = ::chooseFolder,
                    onQueue = ::queueWithNotificationPermission,
                    onOpen = { launch(outputStore.openIntent(it)) },
                    onShare = { launch(outputStore.shareIntent(it)) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        populateFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshFolderGrant()
    }

    private fun chooseFolder() {
        folderPicker.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            },
        )
    }

    private fun queueWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            queueAfterPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.queue()
        }
    }

    private fun populateFromIntent(intent: Intent?) {
        val candidate = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { SHARED_HTTPS.find(it)?.value ?: it }
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!candidate.isNullOrBlank()) viewModel.setUrl(candidate)
    }

    private fun launch(intent: Intent?) {
        if (intent == null) return
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The job card remains available when no installed app can handle the MIME type.
        }
    }

    companion object {
        private val SHARED_HTTPS = Regex("""https://\S+""")
    }
}
