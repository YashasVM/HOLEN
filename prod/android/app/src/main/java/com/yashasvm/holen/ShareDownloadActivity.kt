package com.yashasvm.holen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.yashasvm.holen.ui.HolenTheme
import com.yashasvm.holen.ui.ShareDownloadScreen

class ShareDownloadActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var sharedUrl: String? = null
    private var queueAfterPermission = false

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
        setFinishOnTouchOutside(true)
        sharedUrl = extractSharedHttps(intent)
        setContent {
            HolenTheme {
                ShareDownloadScreen(
                    viewModel = viewModel,
                    hasValidUrl = sharedUrl != null,
                    onDownload = ::queueWithNotificationPermission,
                    onOpenHolen = ::openHolen,
                    onDismiss = ::finish,
                    onQueued = ::confirmQueued,
                )
            }
        }
        if (savedInstanceState == null) window.decorView.post {
            sharedUrl?.let(viewModel::receiveIncomingUrl)
        }
    }

    override fun onStart() {
        super.onStart()
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl = extractSharedHttps(intent)
        sharedUrl?.let(viewModel::receiveIncomingUrl)
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

    private fun openHolen() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                sharedUrl?.let { putExtra(MainActivity.EXTRA_SHARED_URL, it) }
            },
        )
        finish()
    }

    private fun confirmQueued() {
        Toast.makeText(this, "Download added to queue. Starting now.", Toast.LENGTH_SHORT).show()
        finish()
    }
}

internal fun extractSharedHttps(intent: Intent?): String? {
    val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        ?: intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    return extractSharedHttps(text)
}

internal fun extractSharedHttps(text: String?): String? =
    text?.let { sharedUrlPattern.findAll(it) }
        ?.map { match -> match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }
        ?.firstOrNull { runCatching { validateHttpsUrl(it) }.isSuccess }

private val sharedUrlPattern = Regex("""https://\S+""")
