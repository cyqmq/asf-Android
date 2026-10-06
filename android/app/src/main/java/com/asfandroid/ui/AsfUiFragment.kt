package com.asfandroid.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import com.asfandroid.core.AsfPaths
import com.asfandroid.databinding.FragmentAsfUiBinding

/** ASF-ui 主界面：加载 ASF 的 Web 管理面板；未运行时显示启动提示。 */
class AsfUiFragment : Fragment() {

    private var _binding: FragmentAsfUiBinding? = null
    private val binding get() = _binding!!

    private var loadStarted = false
    private var pageLoadFailed = false

    /** 点击「前往仪表盘启动」时的回调。 */
    var onGoDashboard: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAsfUiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWebView()
        binding.btnGoDashboard.setOnClickListener { onGoDashboard?.invoke() }
        binding.btnWebBack.setOnClickListener {
            if (binding.webview.canGoBack()) binding.webview.goBack()
        }
        binding.btnWebForward.setOnClickListener {
            if (binding.webview.canGoForward()) binding.webview.goForward()
        }
        binding.btnWebReload.setOnClickListener { binding.webview.reload() }
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
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                pageLoadFailed = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                binding.progress.visibility = View.GONE
                pageLoadFailed = true
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                binding.progress.visibility = View.GONE
                pageLoadFailed = true
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }
    }

    /** 由 MainActivity 在 ASF 就绪时调用；只在首次加载，失败才重试。 */
    fun loadAsfUi() {
        if (!isAdded || view == null) return
        val url = AsfPaths.asfUrl()
        if (!loadStarted) {
            loadStarted = true
            pageLoadFailed = false
            binding.webview.loadUrl(url)
        } else if (pageLoadFailed) {
            pageLoadFailed = false
            binding.webview.reload()
        }
    }

    /** ASF 停止时重置加载状态，避免重启后不刷新。 */
    fun onAsfStopped() {
        loadStarted = false
    }

    /** 未运行时显示提示层，隐藏 WebView。 */
    fun showNotRunning() {
        if (_binding == null) return
        binding.layoutNotRunning.visibility = View.VISIBLE
        binding.webview.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.layoutWebNav.visibility = View.GONE
    }

    /** 运行时显示 WebView，隐藏提示层。 */
    fun showWebView() {
        if (_binding == null) return
        binding.layoutNotRunning.visibility = View.GONE
        binding.webview.visibility = View.VISIBLE
        binding.layoutWebNav.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}