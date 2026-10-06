package com.asfandroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.asfandroid.R
import com.asfandroid.core.AsfPaths
import java.io.File

/** 日志查看器：列出 asf-data/logs 下的日志文件并显示内容。 */
class LogActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var logs: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        listView = findViewById(R.id.list_logs)
        emptyView = findViewById(R.id.tv_log_empty)

        listView.setOnItemClickListener { _, _, position, _ ->
            showLog(logs[position])
        }

        findViewById<View>(R.id.btn_log_refresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.btn_copy_logs).setOnClickListener { copyAllLogs() }
        refresh()
    }

    private fun refresh() {
        val logsDir = AsfPaths.logsDir(this)
        logs = (logsDir.listFiles { f -> f.isFile && (f.extension == "txt" || f.extension == "log") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        if (logs.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            listView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            listView.visibility = View.VISIBLE
            listView.adapter = ArrayAdapter(
                this,
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
            Toast.makeText(this, R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ASFAndroid 日志", sb.toString()))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showLog(file: File) {
        val content = try {
            file.readText().takeLast(200_000)
        } catch (e: Exception) {
            Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        val textView = TextView(this).apply {
            text = content
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(textView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}