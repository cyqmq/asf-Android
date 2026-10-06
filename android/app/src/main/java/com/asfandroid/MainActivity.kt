package com.asfandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.asfandroid.core.AsfController
import com.asfandroid.core.AsfStatus
import com.asfandroid.databinding.ActivityMainBinding
import com.asfandroid.ui.AsfUiFragment
import com.asfandroid.ui.ConfigFragment
import com.asfandroid.ui.DashboardFragment
import com.asfandroid.ui.LogsFragment
import com.asfandroid.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private val dashboardFragment = DashboardFragment()
    private val asfUiFragment = AsfUiFragment()
    private val logsFragment = LogsFragment()
    private val configFragment = ConfigFragment()
    private val settingsFragment = SettingsFragment()

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
                    showTab(configFragment)
                    true
                }
                R.id.action_logs -> {
                    showTab(logsFragment)
                    binding.bottomNav.selectedItemId = R.id.nav_logs
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

        asfUiFragment.onGoDashboard = {
            binding.bottomNav.selectedItemId = R.id.nav_dashboard
            showTab(dashboardFragment)
        }
        settingsFragment.onOpenLaunchOptions = {
            showTab(configFragment)
        }

        setupFragments()
        setupBottomNav()
        setupSwitch()

        maybeRequestNotificationPermission()
    }

    private fun setupFragments() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, dashboardFragment, "dashboard")
            .add(R.id.fragment_container, asfUiFragment, "asf_ui")
            .add(R.id.fragment_container, logsFragment, "logs")
            .add(R.id.fragment_container, configFragment, "config")
            .add(R.id.fragment_container, settingsFragment, "settings")
            .hide(asfUiFragment)
            .hide(logsFragment)
            .hide(configFragment)
            .hide(settingsFragment)
            .commit()
    }

    private fun showTab(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .hide(dashboardFragment)
            .hide(asfUiFragment)
            .hide(logsFragment)
            .hide(configFragment)
            .hide(settingsFragment)
            .show(fragment)
            .commit()
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> showTab(dashboardFragment)
                R.id.nav_webview -> showTab(asfUiFragment)
                R.id.nav_logs -> showTab(logsFragment)
                R.id.nav_settings -> showTab(settingsFragment)
            }
            true
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

        dashboardFragment.updateStatus(status)

        if (status == AsfStatus.RUNNING) {
            asfUiFragment.showWebView()
            asfUiFragment.loadAsfUi()
        } else {
            asfUiFragment.showNotRunning()
            asfUiFragment.onAsfStopped()
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
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                Toast.makeText(this, R.string.battery_optimization_already, Toast.LENGTH_SHORT).show()
            }
        }
    }
}