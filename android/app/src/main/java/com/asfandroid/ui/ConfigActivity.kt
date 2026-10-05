package com.asfandroid.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.asfandroid.R
import com.asfandroid.core.AsfPaths
import java.io.File

/**
 * 配置文件编辑器：列出 asf-data/config 下的 JSON 文件，点击即可编辑保存。
 * 修改配置后需在 ASF 中执行 reload（或重启服务）才会生效。
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        listView = findViewById(R.id.list_config)
        emptyView = findViewById(R.id.tv_empty)

        listView.setOnItemClickListener { _, _, position, _ ->
            val file = files[position]
            showEditor(file)
        }

        findViewById<View>(R.id.btn_refresh).setOnClickListener { refresh() }
        refresh()
    }

    private fun refresh() {
        val configDir = AsfPaths.configDir(this)
        files = (configDir.listFiles { f -> f.isFile && f.extension in setOf("json") } ?: emptyArray())
            .sortedBy { it.name }
        if (files.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            listView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            listView.visibility = View.VISIBLE
            listView.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                files.map { "${it.name}  (${it.length()} B)" }
            )
        }
    }

    private fun showEditor(file: File) {
        val content = if (file.exists()) file.readText() else ""
        val editText = EditText(this).apply {
            setText(content)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            minLines = 12
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                try {
                    file.writeText(editText.text.toString())
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(this, "${getString(R.string.save_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}