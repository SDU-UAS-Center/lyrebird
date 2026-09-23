package com.lyrebird.rc.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import com.lyrebird.rc.R
import com.lyrebird.rc.databinding.FragMegaphonePageBinding
import com.lyrebird.rc.databinding.FragMegaphoneRealtimeBinding
import com.lyrebird.rc.databinding.FragMopCenterPageBinding
import com.lyrebird.rc.models.MegaphoneVM
import com.lyrebird.rc.util.ToastUtils

class RealTimeFragment: DJIFragment() {
    private val megaphoneVM: MegaphoneVM by activityViewModels()
    private var recordStarted:Boolean = false
    private var binding: FragMegaphoneRealtimeBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragMegaphoneRealtimeBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initBtnListener()
    }

    /**
     * The microphone is the megaphone's, so it is asked for on the button that records rather
     * than at startup. This page is the only caller of [MegaphoneVM.startRecord], which records
     * through DJI's AudioRecordHandler; nothing else in the app uses the microphone.
     */
    private val microphoneLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                ToastUtils.showToast(getString(R.string.megaphone_mic_denied))
            }
        }

    private fun initBtnListener() {
        binding?.btnRealtime?.setOnClickListener {
            when {
                recordStarted -> stopRecording()
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> startRecording()
                else ->
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.megaphone_mic_title)
                        .setMessage(R.string.megaphone_mic_message)
                        .setNegativeButton(R.string.permission_rationale_later, null)
                        .setPositiveButton(R.string.permission_rationale_continue) { _, _ ->
                            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        .show()
            }
        }

        binding?.cbAgc?.setOnCheckedChangeListener { _, isChecked ->
           megaphoneVM.enableAgc(isChecked)
        }

    }

    private fun startRecording() {
        megaphoneVM.startRecord()
        binding?.btnRealtime?.setImageDrawable(requireContext().getDrawable(R.drawable.ic_official_speaker_real_time_stop))
        binding?.tvSpeakingControl?.setText(R.string.btn_press_to_stop_record)
        recordStarted = true
    }

    private fun stopRecording() {
        megaphoneVM.stopRecord()
        binding?.btnRealtime?.setImageDrawable(requireContext().getDrawable(R.drawable.ic_official_speaker_real_time_start))
        binding?.tvSpeakingControl?.setText(R.string.btn_press_to_start_record)
        recordStarted = false
    }
}