package com.asfandroid.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.asfandroid.R
import com.asfandroid.core.AsfPaths
import com.asfandroid.databinding.FragmentConfigBinding
import java.io.File

/** 配置文件编辑器：列出 asf-data/config 下的 JSON 文件，点击即可编辑保存。 */
class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!
    private var files: List<File> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.listConfig.setOnItemClickListener { _, _, position, _ ->
            showEditor(files[position])
        }
        binding.btnRefresh.setOnClickListener { refresh() }
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    private fun refresh() {
        if (!isAdded) return
        val configDir = AsfPaths.configDir(requireContext())
        files = (configDir.listFiles { f -> f.isFile && f.extension in setOf("json") } ?: emptyArray())
            .sortedBy { it.name }
        if (files.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.listConfig.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.listConfig.visibility = View.VISIBLE
            binding.listConfig.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                files.map { "${it.name}  (${it.length()} B)" }
            )
        }
    }

    private fun showEditor(file: File) {
        val content = if (file.exists()) file.readText() else ""
        val editText = EditText(requireContext()).apply {
            setText(content)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            minLines = 12
        }

        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                try {
                    file.writeText(editText.text.toString())
                    Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "${getString(R.string.save_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}