package com.asfandroid.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.asfandroid.R
import com.asfandroid.core.AsfController
import com.asfandroid.core.AsfPaths
import com.asfandroid.core.AsfStatus
import com.asfandroid.databinding.FragmentDashboardBinding
import org.json.JSONObject
import java.io.File

/** 仪表盘和启动页：显示服务状态、端点地址，提供启动/停止按钮。 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    /** 点击「打开 WebView」时的回调。 */
    var onOpenWebView: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnStartStop.setOnClickListener {
            val running = AsfController.getStatus(requireContext()) != AsfStatus.STOPPED
            if (running) {
                AsfController.stop(requireContext())
            } else {
                AsfController.start(requireContext())
            }
        }
        binding.btnOpenWebview.setOnClickListener { onOpenWebView?.invoke() }
    }

    override fun onResume() {
        super.onResume()
        refreshEndpoint()
    }

    /** 由 MainActivity 每秒轮询调用，刷新状态显示。 */
    fun updateStatus(status: AsfStatus) {
        if (!isAdded || _binding == null) return
        val running = status != AsfStatus.STOPPED
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
        binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(requireContext(), dotColor)
        binding.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), textColor))
        binding.btnStartStop.setText(if (running) R.string.stop_asf else R.string.start_asf)
    }

    private fun refreshEndpoint() {
        if (!isAdded || _binding == null) return
        binding.tvEndpoint.text = readIpcUrl() ?: AsfPaths.asfUrl()
        binding.tvExternalHint.setText(
            if (readIpcUrl() != null) R.string.external_access_enabled else R.string.external_access_disabled
        )
    }

    private fun readIpcUrl(): String? {
        val dir = AsfPaths.configDir(requireContext())
        val file = File(dir, "IPC.config")
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            root.getJSONObject("Kestrel")
                .getJSONObject("Endpoints")
                .getJSONObject("Http")
                .getString("Url")
        }.getOrNull()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}