package com.overdrive.app.ui.fragment.settings

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import com.overdrive.app.communication.RemoteCommunicationSettings
import com.overdrive.app.overlay.MessageOverlayService
import com.overdrive.app.overlay.OverlayPermissionChecker
import com.overdrive.app.services.CabinAudioCaptureService
import com.overdrive.app.services.RemoteVoiceService
import java.util.concurrent.Executors

/**
 * Shared driver for the portrait and landscape Remote Communication card.
 *
 * Config reads/writes use a private worker because UnifiedConfigManager may
 * perform a blocking daemon IPC round-trip from the app process.
 */
class RemoteCommunicationSettingsBinder(private val root: View) {
    private val context = root.context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RemoteCommunicationSettings").apply { isDaemon = true }
    }

    private val voiceSwitch = root.findViewById<SwitchMaterial>(R.id.swRemoteVoice)
    private val listenerSwitch =
        root.findViewById<SwitchMaterial>(R.id.swRemoteListener)
    private val messagesSwitch =
        root.findViewById<SwitchMaterial>(R.id.swRemoteMessages)
    private val emergencySwitch =
        root.findViewById<SwitchMaterial>(R.id.swRemoteEmergency)
    private val outputOverrideSwitch =
        root.findViewById<SwitchMaterial>(R.id.swRemoteOutputOverride)
    private val outputSlider =
        root.findViewById<Slider>(R.id.sliderRemoteOutputLevel)
    private val outputValue =
        root.findViewById<TextView>(R.id.tvRemoteOutputLevel)
    private val outputChannelGroup =
        root.findViewById<MaterialButtonToggleGroup>(R.id.toggleRemoteAudioChannel)
    private val outputMediaButton =
        root.findViewById<MaterialButton>(R.id.btnRemoteAudioMedia)
    private val outputNavigationButton =
        root.findViewById<MaterialButton>(R.id.btnRemoteAudioNavigation)
    private val overlayPermission =
        root.findViewById<TextView>(R.id.tvRemoteOverlayPermission)
    private val testSpeaker =
        root.findViewById<MaterialButton>(R.id.btnRemoteTestSpeaker)
    private val testMessage =
        root.findViewById<MaterialButton>(R.id.btnRemoteTestMessage)

    @Volatile private var destroyed = false
    private var applying = false
    private var persistedOutputLevel = 70

    init {
        bindControls()
        refresh()
    }

    fun refresh() {
        if (destroyed) return
        worker.execute {
            val overlaysAllowed = OverlayPermissionChecker.isGranted(context)
            val snapshot = runCatching {
                RemoteCommunicationSettings.load()
            }.getOrNull()
            post {
                renderOverlayPermission(overlaysAllowed)
                snapshot?.let(::render)
            }
        }
    }

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    private fun bindControls() {
        root.findViewById<View>(R.id.rowRemoteVoice)?.setOnClickListener {
            if (voiceSwitch.isEnabled) voiceSwitch.isChecked = !voiceSwitch.isChecked
        }
        root.findViewById<View>(R.id.rowRemoteMessages)?.setOnClickListener {
            if (messagesSwitch.isEnabled) {
                messagesSwitch.isChecked = !messagesSwitch.isChecked
            }
        }
        root.findViewById<View>(R.id.rowRemoteListener)?.setOnClickListener {
            if (listenerSwitch.isEnabled) {
                listenerSwitch.isChecked = !listenerSwitch.isChecked
            }
        }
        root.findViewById<View>(R.id.rowRemoteOutputOverride)?.setOnClickListener {
            if (outputOverrideSwitch.isEnabled) {
                outputOverrideSwitch.isChecked = !outputOverrideSwitch.isChecked
            }
        }
        root.findViewById<View>(R.id.rowRemoteEmergency)?.setOnClickListener {
            emergencySwitch.isChecked = !emergencySwitch.isChecked
        }
        root.findViewById<View>(R.id.rowRemoteOverlayPermission)?.setOnClickListener {
            openOverlayPermission()
        }

        voiceSwitch.setOnCheckedChangeListener { _, checked ->
            if (!applying) {
                persist(
                    write = {
                        RemoteCommunicationSettings.update(
                            checked, null, null, null)
                    },
                    onSuccess = {
                        if (!checked) RemoteVoiceService.stopNow(context)
                    },
                    onFailure = ::refresh
                )
            }
        }
        listenerSwitch.setOnCheckedChangeListener { _, checked ->
            if (!applying) {
                persist(
                    write = {
                        RemoteCommunicationSettings.updateListenerEnabled(checked)
                    },
                    onSuccess = {
                        if (!checked) CabinAudioCaptureService.stopNow(context)
                    },
                    onFailure = ::refresh
                )
            }
        }
        messagesSwitch.setOnCheckedChangeListener { _, checked ->
            if (!applying) {
                persist(
                    write = {
                        RemoteCommunicationSettings.update(
                            null, null, checked, null)
                    },
                    onSuccess = {
                        if (!checked) dismissRemoteMessagesNow()
                    },
                    onFailure = ::refresh
                )
            }
        }
        emergencySwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) return@setOnCheckedChangeListener
            applyEmergencyUi(checked)
            persist(
                write = {
                    RemoteCommunicationSettings.update(
                        null, null, null, checked)
                },
                onSuccess = {
                    if (checked) stopRemoteCommunicationNow()
                },
                onFailure = ::refresh
            )
        }
        outputOverrideSwitch.setOnCheckedChangeListener { _, checked ->
            if (applying) return@setOnCheckedChangeListener
            applyOutputOverrideUi(checked, emergencySwitch.isChecked)
            persist(
                write = {
                    RemoteCommunicationSettings.update(
                        null, null, checked, null, null)
                },
                onFailure = ::refresh
            )
        }
        outputChannelGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked || applying) return@addOnButtonCheckedListener
            val channel =
                if (checkedId == R.id.btnRemoteAudioNavigation) {
                    RemoteCommunicationSettings.AUDIO_CHANNEL_NAVIGATION
                } else {
                    RemoteCommunicationSettings.AUDIO_CHANNEL_MEDIA
                }
            persist(
                write = {
                    RemoteCommunicationSettings.updateAudioChannel(channel)
                },
                onFailure = ::refresh
            )
        }

        outputSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                outputValue.text = root.resources.getString(
                    R.string.settings_remote_output_value,
                    value.toInt())
            }
        }
        outputSlider.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit

                override fun onStopTrackingTouch(slider: Slider) {
                    if (applying) return
                    val requested = slider.value.toInt()
                    if (requested == persistedOutputLevel) return
                    persist(
                        write = {
                            RemoteCommunicationSettings.update(
                                null, requested, null, null)
                        },
                        onSuccess = {
                            persistedOutputLevel = requested
                        },
                        onFailure = ::refresh
                    )
                }
            }
        )

        testSpeaker.setOnClickListener {
            if (!voiceSwitch.isChecked || emergencySwitch.isChecked) return@setOnClickListener
            RemoteVoiceService.startSpeakerTest(context)
            Toast.makeText(
                root.context,
                R.string.settings_remote_speaker_started,
                Toast.LENGTH_SHORT
            ).show()
        }
        testMessage.setOnClickListener {
            if (!messagesSwitch.isChecked || emergencySwitch.isChecked) {
                return@setOnClickListener
            }
            if (!OverlayPermissionChecker.isGranted(context)) {
                Toast.makeText(
                    root.context,
                    R.string.settings_remote_overlay_required,
                    Toast.LENGTH_SHORT
                ).show()
                openOverlayPermission()
                return@setOnClickListener
            }
            val intent = Intent(context, MessageOverlayService::class.java)
                .putExtra("kind", "toast")
                .putExtra(
                    "message",
                    root.resources.getString(R.string.settings_remote_test_message_body))
                .putExtra("severity", "info")
                .putExtra("position", "top")
                .putExtra("duration", "short")
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun render(snapshot: RemoteCommunicationSettings.Snapshot) {
        applying = true
        voiceSwitch.isChecked = snapshot.voiceEnabled
        listenerSwitch.isChecked = snapshot.listenerEnabled
        messagesSwitch.isChecked = snapshot.messagesEnabled
        emergencySwitch.isChecked = snapshot.emergencyDisabled
        outputOverrideSwitch.isChecked =
            snapshot.outputLevelOverrideEnabled
        outputChannelGroup.check(
            if (snapshot.audioChannel
                == RemoteCommunicationSettings.AUDIO_CHANNEL_NAVIGATION
            ) {
                R.id.btnRemoteAudioNavigation
            } else {
                R.id.btnRemoteAudioMedia
            }
        )
        persistedOutputLevel = snapshot.outputLevel
        outputSlider.value = snapshot.outputLevel.toFloat()
        applying = false
        applyEmergencyUi(snapshot.emergencyDisabled)
    }

    private fun renderOverlayPermission(allowed: Boolean) {
        overlayPermission.setText(
            if (allowed) {
                R.string.settings_remote_overlay_allowed
            } else {
                R.string.settings_remote_overlay_required
            }
        )
        overlayPermission.setTextColor(
            if (allowed) {
                resolveColor(androidx.appcompat.R.attr.colorPrimary)
            } else {
                resolveColor(androidx.appcompat.R.attr.colorError)
            }
        )
    }

    private fun applyEmergencyUi(disabled: Boolean) {
        voiceSwitch.isEnabled = !disabled
        listenerSwitch.isEnabled = !disabled
        messagesSwitch.isEnabled = !disabled
        outputChannelGroup.isEnabled = !disabled
        outputMediaButton.isEnabled = !disabled
        outputNavigationButton.isEnabled = !disabled
        applyOutputOverrideUi(outputOverrideSwitch.isChecked, disabled)
        testSpeaker.isEnabled = !disabled && voiceSwitch.isChecked
        testMessage.isEnabled = !disabled && messagesSwitch.isChecked
        root.findViewById<View>(R.id.rowRemoteVoice)?.alpha =
            if (disabled) 0.5f else 1f
        root.findViewById<View>(R.id.rowRemoteListener)?.alpha =
            if (disabled) 0.5f else 1f
        root.findViewById<View>(R.id.rowRemoteMessages)?.alpha =
            if (disabled) 0.5f else 1f
        root.findViewById<View>(R.id.rowRemoteAudioChannel)?.alpha =
            if (disabled) 0.5f else 1f
    }

    private fun applyOutputOverrideUi(enabled: Boolean, emergencyDisabled: Boolean) {
        outputOverrideSwitch.isEnabled = !emergencyDisabled
        outputSlider.isEnabled = enabled && !emergencyDisabled
        outputValue.text = if (enabled) {
            root.resources.getString(
                R.string.settings_remote_output_value,
                outputSlider.value.toInt())
        } else {
            root.resources.getString(R.string.settings_remote_output_car_volume)
        }
        root.findViewById<View>(R.id.rowRemoteOutputLevel)?.alpha =
            if (enabled && !emergencyDisabled) 1f else 0.5f
        outputSlider.alpha =
            if (enabled && !emergencyDisabled) 1f else 0.5f
    }

    private fun persist(
        write: () -> Boolean,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit
    ) {
        worker.execute {
            val saved = runCatching(write).getOrDefault(false)
            post {
                if (saved) {
                    onSuccess()
                    applyEmergencyUi(emergencySwitch.isChecked)
                } else {
                    Toast.makeText(
                        root.context,
                        R.string.settings_remote_save_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                    onFailure()
                }
            }
        }
    }

    private fun stopRemoteCommunicationNow() {
        RemoteVoiceService.stopNow(context)
        CabinAudioCaptureService.stopNow(context)
        dismissRemoteMessagesNow()
    }

    private fun dismissRemoteMessagesNow() {
        context.sendBroadcast(Intent(MessageOverlayService.ACTION_DISMISS))
        context.stopService(Intent(context, MessageOverlayService::class.java))
    }

    private fun openOverlayPermission() {
        if (OverlayPermissionChecker.isGranted(context)) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun resolveColor(attribute: Int): Int {
        return MaterialColors.getColor(root, attribute, Color.GRAY)
    }

    private fun post(block: () -> Unit) {
        if (destroyed) return
        mainHandler.post {
            if (!destroyed && root.isAttachedToWindow) block()
        }
    }
}
