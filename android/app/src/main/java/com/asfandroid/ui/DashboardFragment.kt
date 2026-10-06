package com.asfandroid.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.asfandroid.R
import com.asfandroid.core.AsfController
import com.asfandroid.core.AsfStatus
import com.asfandroid.databinding.FragmentDashboardBinding

/** 仪表盘和启动页：显示服务状态，提供启动/停止按钮。 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}