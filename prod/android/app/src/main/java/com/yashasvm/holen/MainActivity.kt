package com.yashasvm.holen

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.toArgb
import com.yashasvm.holen.ui.HolenBackground
import com.yashasvm.holen.ui.HolenScreen
import com.yashasvm.holen.ui.HolenTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            lifecycleScope.launch {
                val persisted = withContext(Dispatchers.IO) {
                    runCatching {
                        outputStore.persistTree(uri, data.flags)
                        check(outputStore.hasValidTreeGrant())
                    }
                }
                if (persisted.isSuccess) {
                    viewModel.folderSelectionSucceeded()
                } else {
                    viewModel.folderSelectionFailed()
                }
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
        if (savedInstanceState == null) populateFromIntent(intent)
        lifecycleScope.launch {
            viewModel.queueEvents.collect {
                Toast.makeText(
                    this@MainActivity,
                    "Download added to queue. Starting now.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        setContent {
            HolenTheme {
                HolenScreen(
                    viewModel = viewModel,
                    onChooseFolder = ::chooseFolder,
                    onQueue = ::queueWithNotificationPermission,
                    onOpen = { launch(outputStore.openIntent(it)) },
                    onShare = { launch(outputStore.shareIntent(it)) },
                    onOpenCreator = {
                        launch(Intent(Intent.ACTION_VIEW, Uri.parse(CREATOR_WEBSITE)))
                    },
                    onOpenSource = {
                        launch(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_GITHUB)))
                    },
                )
            }
        }
        viewModel.recoverQueue()
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
        val candidate = intent?.getStringExtra(EXTRA_SHARED_URL) ?: when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { SHARED_HTTPS.find(it)?.value ?: it }
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!candidate.isNullOrBlank()) viewModel.receiveIncomingUrl(candidate)
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
        private const val CREATOR_WEBSITE = "https://yashasvm.pages.dev"
        private const val PROJECT_GITHUB = "https://github.com/YashasVM/HOLEN"
        const val EXTRA_SHARED_URL = "com.yashasvm.holen.extra.SHARED_URL"
    }
}
