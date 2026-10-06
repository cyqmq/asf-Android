package com.asfandroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.asfandroid.R
import com.asfandroid.core.AsfPaths
import com.asfandroid.databinding.FragmentLogsBinding
import java.io.File

/** 日志查看器：列出 asf-data/logs 下的日志文件并显示内容。 */
class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private var logs: List<File> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.listLogs.setOnItemClickListener { _, _, position, _ ->
            showLog(logs[position])
        }
        binding.btnLogRefresh.setOnClickListener { refresh() }
        binding.btnCopyLogs.setOnClickListener { copyAllLogs() }
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    private fun refresh() {
        if (!isAdded) return
        val logsDir = AsfPaths.logsDir(requireContext())
        logs = (logsDir.listFiles { f -> f.isFile && (f.extension == "txt" || f.extension == "log") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        if (logs.isEmpty()) {
            binding.tvLogEmpty.visibility = View.VISIBLE
            binding.listLogs.visibility = View.GONE
        } else {
            binding.tvLogEmpty.visibility = View.GONE
            binding.listLogs.visibility = View.VISIBLE
            binding.listLogs.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                logs.map { "${it.name}  (${it.length()} B)" }
            )
        }
    }

    private fun copyAllLogs() {
        val sb = StringBuilder()
        for (file in logs) {
            sb.append("===== ${file.name} (${file.length()} B) =====\n")
            sb.append(
                runCatching { file.readText().takeLast(500_000) }
                    .getOrElse { "读取失败: ${it.message}" }
            )
            sb.append("\n\n")
        }
        if (sb.isEmpty()) {
            Toast.makeText(requireContext(), R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ASFAndroid 日志", sb.toString()))
        Toast.makeText(requireContext(), R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showLog(file: File) {
        val content = try {
            file.readText().takeLast(200_000)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        val textView = TextView(requireContext()).apply {
            text = content
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(textView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}