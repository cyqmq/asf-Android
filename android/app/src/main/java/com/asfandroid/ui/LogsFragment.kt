package com.asfandroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.asfandroid.R
import com.asfandroid.core.AsfPaths
import com.asfandroid.databinding.FragmentLogsBinding
import java.io.File

/** 实时日志页：跟随 asf-console.log 滚动显示 ASF 控制台输出。 */
class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private var autoScroll = true
    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnToggleAutoscroll.setText(if (autoScroll) R.string.autoscroll_on else R.string.autoscroll_off)
        binding.btnToggleAutoscroll.setOnClickListener {
            autoScroll = !autoScroll
            binding.btnToggleAutoscroll.setText(if (autoScroll) R.string.autoscroll_on else R.string.autoscroll_off)
            if (autoScroll) scrollToBottom()
        }
        binding.btnCopyLogs.setOnClickListener { copyLogs() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    private fun refresh() {
        if (!isAdded || _binding == null) return
        val logFile = File(AsfPaths.logsDir(requireContext()), "asf-console.log")
        val content = if (logFile.exists()) {
            runCatching { logFile.readText().takeLast(200_000) }.getOrElse { "读取失败: ${it.message}" }
        } else {
            "（暂无 ASF 控制台输出）"
        }
        binding.tvLogContent.text = content
        if (autoScroll) scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun copyLogs() {
        val content = binding.tvLogContent.text.toString()
        if (content.isEmpty()) {
            Toast.makeText(requireContext(), R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ASFAndroid 日志", content))
        Toast.makeText(requireContext(), R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}