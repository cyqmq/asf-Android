package com.asfandroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.asfandroid.core.AsfController
import com.asfandroid.core.AsfPaths
import com.asfandroid.core.AsfStatus
import com.asfandroid.databinding.ActivityMainBinding
import com.asfandroid.ui.ConfigActivity
import com.asfandroid.ui.LogActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pageLoadFailed = false
    private val handler = Handler(Looper.getMainLooper())
    private var webViewLoadStarted = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.menu.findItem(R.id.action_autostart).isChecked =
            getSharedPreferences("asf_settings", MODE_PRIVATE).getBoolean("boot_autostart", false)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_config -> {
                    startActivity(Intent(this, ConfigActivity::class.java))
                    true
                }
                R.id.action_logs -> {
                    startActivity(Intent(this, LogActivity::class.java))
                    true
                }
                R.id.action_autostart -> {
                    item.isChecked = !item.isChecked
                    getSharedPreferences("asf_settings", MODE_PRIVATE)
                        .edit()
                        .putBoolean("boot_autostart", item.isChecked)
                        .apply()
                    Toast.makeText(this, R.string.autostart_saved, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_battery -> {
                    requestIgnoreBatteryOptimizations()
                    true
                }
                else -> false
            }
        }

        setupWebView()
        setupSwitch()

        maybeRequestNotificationPermission()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webview
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString + " ASFAndroid"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = android.view.View.GONE
                pageLoadFailed = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                binding.progress.visibility = android.view.View.GONE
                pageLoadFailed = true
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                binding.progress.visibility = android.view.View.GONE
                pageLoadFailed = true
            }

            // ASF-ui 是 http，且可能包含自签名场景；此处仅用于局域网/本机 IPC。
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }
    }

    private fun setupSwitch() {
        binding.switchAsf.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                AsfController.start(this)
                binding.tvStatus.setText(R.string.status_starting)
            } else {
                AsfController.stop(this)
                binding.tvStatus.setText(R.string.status_stopping)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    private fun updateStatus() {
        val status = AsfController.getStatus(this)
        binding.switchAsf.setOnCheckedChangeListener(null)
        binding.switchAsf.isChecked = status != AsfStatus.STOPPED
        binding.switchAsf.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                AsfController.start(this)
                binding.tvStatus.setText(R.string.status_starting)
            } else {
                AsfController.stop(this)
                binding.tvStatus.setText(R.string.status_stopping)
            }
        }

        binding.tvStatus.setText(
            when (status) {
                AsfStatus.RUNNING -> R.string.status_running
                AsfStatus.STARTING -> R.string.status_starting
                AsfStatus.STOPPED -> R.string.status_stopped
            }
        )

        val (dotColor, textColor) = when (status) {
            AsfStatus.RUNNING -> R.color.status_running to R.color.status_running
            AsfStatus.STARTING -> R.color.status_starting to R.color.status_starting
            AsfStatus.STOPPED -> R.color.status_stopped to R.color.status_stopped
        }
        binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, dotColor)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, textColor))

        if (status == AsfStatus.RUNNING) {
            val url = AsfPaths.asfUrl()
            val webView = binding.webview
            // 只在首次就绪时加载一次；加载失败才重试，避免 ASF-ui 内 URL 变化导致无限刷新
            if (!webViewLoadStarted) {
                webViewLoadStarted = true
                pageLoadFailed = false
                webView.loadUrl(url)
            } else if (pageLoadFailed) {
                pageLoadFailed = false
                webView.reload()
            }
        } else {
            webViewLoadStarted = false
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    // 部分 ROM 不支持该 Intent，引导到系统电池设置页
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                Toast.makeText(this, R.string.battery_optimization_already, Toast.LENGTH_SHORT).show()
            }
        }
    }
}