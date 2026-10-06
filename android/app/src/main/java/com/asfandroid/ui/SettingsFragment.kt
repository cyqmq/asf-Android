package com.asfandroid.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.asfandroid.BuildConfig
import com.asfandroid.R
import com.asfandroid.core.AsfPaths
import com.asfandroid.databinding.FragmentSettingsBinding
import org.json.JSONObject
import java.io.File

/** 设置页：外部访问开关、IP/端口、启动项、仓库入口、关于。 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    var onOpenLaunchOptions: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadCurrent()

        binding.switchExternal.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                saveIpcConfig(currentIp(), currentPort())
            } else {
                ipcConfigFile()?.delete()
            }
            toastSaved()
        }

        binding.rowIp.setOnClickListener { editValue(getString(R.string.ip_address), currentIp()) { newIp ->
            saveIpcConfig(newIp, currentPort())
            loadCurrent()
            toastSaved()
        } }

        binding.rowPort.setOnClickListener { editValue(getString(R.string.ip_port), currentPort()) { newPort ->
            saveIpcConfig(currentIp(), newPort)
            loadCurrent()
            toastSaved()
        } }

        binding.rowLaunchOptions.setOnClickListener { onOpenLaunchOptions?.invoke() }
        binding.rowRepository.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cyqmq/asf-Android")))
            }
        }
        binding.rowAbout.setOnClickListener { showAbout() }
    }

    private fun ipcConfigFile(): File? {
        val dir = AsfPaths.configDir(requireContext())
        dir.mkdirs()
        return File(dir, "IPC.config")
    }

    private fun readIpcUrl(): String? {
        val file = ipcConfigFile() ?: return null
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            root.getJSONObject("Kestrel")
                .getJSONObject("Endpoints")
                .getJSONObject("Http")
                .getString("Url")
        }.getOrNull()
    }

    private fun currentIp(): String {
        val url = readIpcUrl()
        if (url != null) {
            return url.substringAfter("://").substringBefore(':')
        }
        return "0.0.0.0"
    }

    private fun currentPort(): String {
        val url = readIpcUrl()
        if (url != null) {
            return url.substringAfterLast(':')
        }
        return "1242"
    }

    private fun saveIpcConfig(ip: String, port: String) {
        val file = ipcConfigFile() ?: return
        val json = JSONObject()
        val kestrel = JSONObject()
        val endpoints = JSONObject()
        val http = JSONObject()
        http.put("Url", "http://$ip:$port")
        endpoints.put("Http", http)
        kestrel.put("Endpoints", endpoints)
        json.put("Kestrel", kestrel)
        file.writeText(json.toString(2))
    }

    private fun loadCurrent() {
        binding.switchExternal.setOnCheckedChangeListener(null)
        binding.switchExternal.isChecked = readIpcUrl() != null
        binding.switchExternal.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                saveIpcConfig(currentIp(), currentPort())
            } else {
                ipcConfigFile()?.delete()
            }
            toastSaved()
        }
        binding.tvIpValue.text = currentIp()
        binding.tvPortValue.text = currentPort()
    }

    private fun editValue(title: String, initial: String, onSave: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(initial)
            hint = if (title == getString(R.string.ip_address)) getString(R.string.input_ip_hint) else getString(R.string.input_port_hint)
            setPadding(24, 24, 24, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) onSave(value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toastSaved() {
        Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showAbout() {
        val message = "ASFAndroid v${BuildConfig.VERSION_NAME}\n\n" +
            "基于 ArchiSteamFarm 的 Android 移植版。\n" +
            "GitHub: github.com/cyqmq/asf-Android"
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.about)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}