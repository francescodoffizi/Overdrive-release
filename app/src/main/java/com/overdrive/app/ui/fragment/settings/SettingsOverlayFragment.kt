package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.overlay.StatusOverlayUiWriter
import com.overdrive.app.roadsense.config.RoadSenseConfig
import com.overdrive.app.roadsense.overlay.RoadSenseOverlayService

/**
 * Settings → Status overlay pane.
 *
 * Four switches for the app's floating status surfaces:
 *  - Camera/recording indicator (REC / PROX)
 *  - Instant replay indicator (CLIP)
 *  - Trip indicator (TRIP)
 *  - RoadSense pill / hazard card
 *
 * The three status-pill flags live in [UnifiedConfigManager]'s `statusOverlay`
 * section; RoadSense uses its existing `roadSense.overlayVisible` flag. Both
 * sections are file-backed so the app and daemon UIDs see one shared value.
 */
class SettingsOverlayFragment : Fragment() {
    private var roadSenseSwitch: SwitchMaterial? = null
    private var roadSenseRow: View? = null
    private var remoteCommunicationBinder: RemoteCommunicationSettingsBinder? = null
    private var roadSenseMasterOn = false
    private var applyingRoadSenseConfig = false

    // Guards the three status-pill listeners while a failed write reverts its
    // switch: setChecked() fires the listener even programmatically, and an
    // unguarded revert would re-enter persist() with the stale value —
    // ping-ponging forever if the write keeps failing.
    private var applyingStatusOverlayConfig = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_overlay, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val swCamera = view.findViewById<SwitchMaterial>(R.id.swOverlayCamera) ?: return
        val swReplay = view.findViewById<SwitchMaterial>(R.id.swOverlayReplay) ?: return
        val swTrip = view.findViewById<SwitchMaterial>(R.id.swOverlayTrip) ?: return
        val swRoadSense =
            view.findViewById<SwitchMaterial>(R.id.swOverlayRoadSense) ?: return
        roadSenseSwitch = swRoadSense
        // Assign before the first refresh, or its row-dimming branch no-ops.
        roadSenseRow = view.findViewById(R.id.rowOverlayRoadSense)

        val cfg = UnifiedConfigManager.getStatusOverlay()
        swCamera.isChecked = cfg.optBoolean("cameraVisible", true)
        swReplay.isChecked = cfg.optBoolean("replayVisible", true)
        swTrip.isChecked = cfg.optBoolean("tripVisible", true)
        refreshRoadSenseSwitch(forceReload = true)

        // Make the whole row clickable as well for forgiveness on a wide
        // head-unit (toggling via the row, not just the thumb, is the BYD
        // muscle memory).
        view.findViewById<View>(R.id.rowOverlayCamera).setOnClickListener {
            swCamera.isChecked = !swCamera.isChecked
        }
        view.findViewById<View>(R.id.rowOverlayReplay).setOnClickListener {
            swReplay.isChecked = !swReplay.isChecked
        }
        view.findViewById<View>(R.id.rowOverlayTrip).setOnClickListener {
            swTrip.isChecked = !swTrip.isChecked
        }
        roadSenseRow?.setOnClickListener {
            swRoadSense.isChecked = !swRoadSense.isChecked
        }

        swCamera.setOnCheckedChangeListener { button, checked ->
            if (!applyingStatusOverlayConfig) persist("cameraVisible", checked, button)
        }
        swReplay.setOnCheckedChangeListener { button, checked ->
            if (!applyingStatusOverlayConfig) persist("replayVisible", checked, button)
        }
        swTrip.setOnCheckedChangeListener { button, checked ->
            if (!applyingStatusOverlayConfig) persist("tripVisible", checked, button)
        }
        swRoadSense.setOnCheckedChangeListener { _, checked ->
            if (!applyingRoadSenseConfig) persistRoadSense(checked)
        }
        remoteCommunicationBinder = RemoteCommunicationSettingsBinder(view)
    }

    override fun onResume() {
        super.onResume()
        // This preference is also exposed on the RoadSense page. Refresh when the
        // user returns so both entry points always display the same stored value.
        refreshRoadSenseSwitch(forceReload = true)
        remoteCommunicationBinder?.refresh()
    }

    override fun onDestroyView() {
        remoteCommunicationBinder?.destroy()
        remoteCommunicationBinder = null
        roadSenseSwitch = null
        roadSenseRow = null
        super.onDestroyView()
    }

    /**
     * Persist the flag OFF the UI thread ([StatusOverlayUiWriter] — the write
     * is a blocking IPC round-trip and updateSection is documented
     * off-looper-only), then nudge the overlay service so the toggle takes
     * effect now instead of on the next 3-10s poll tick.
     * StatusOverlayService.onStartCommand re-uses the existing instance and
     * cancels any in-flight delayed poll, firing one synchronously.
     *
     * On a failed write (daemon down and no local write possible) the switch
     * reverts, mirroring the RoadSense switch below — the control never lies
     * about the stored value.
     */
    private fun persist(key: String, value: Boolean, button: android.widget.CompoundButton) {
        val appContext = context?.applicationContext
        StatusOverlayUiWriter.write(key, value) { ok ->
            if (ok) {
                appContext?.let {
                    com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(it)
                }
            } else if (view != null) {  // fragment view still alive
                applyingStatusOverlayConfig = true
                button.isChecked = !value
                applyingStatusOverlayConfig = false
            }
        }
    }

    private fun persistRoadSense(visible: Boolean) {
        // setChecked() fires the listener even on a disabled switch, so the
        // master gate has to be enforced here too, not just via row.isEnabled.
        if (!roadSenseMasterOn) {
            refreshRoadSenseSwitch(forceReload = false)
            return
        }
        // Off the looper with one retry, exactly like persist() above. Writing inline here
        // blocked the UI thread on IPC, and an app-UID write that is deferred until the daemon
        // provisions the stable .lock inode returned false, so the switch silently snapped back
        // and RoadSense could not be enabled/disabled at all shortly after boot.
        StatusOverlayUiWriter.writeWith(
            "roadSense.overlayVisible",
            { ok ->
                if (ok) {
                    context?.let { RoadSenseOverlayService.syncWithConfig(it) }
                } else if (view != null) {  // fragment view still alive
                    refreshRoadSenseSwitch(forceReload = true)
                }
            }
        ) { RoadSenseConfig.setOverlayVisible(visible) }
    }

    private fun refreshRoadSenseSwitch(forceReload: Boolean) {
        val toggle = roadSenseSwitch ?: return
        val snapshot = try {
            RoadSenseConfig.snapshot(forceReload)
        } catch (_: Throwable) {
            // Unknown state: leave the control untouched but non-editable rather
            // than clickable-and-undimmed.
            roadSenseMasterOn = false
            setRoadSenseRowEnabled(false)
            return
        }
        applyingRoadSenseConfig = true
        toggle.isChecked = snapshot.overlayVisible
        applyingRoadSenseConfig = false
        // Dim the row when the master switch is off, or the toggle reads ON
        // with no overlay on screen.
        roadSenseMasterOn = snapshot.enabled
        setRoadSenseRowEnabled(snapshot.enabled)
    }

    private fun setRoadSenseRowEnabled(enabled: Boolean) {
        roadSenseSwitch?.isEnabled = enabled
        roadSenseRow?.let { row ->
            row.isEnabled = enabled
            row.alpha = if (enabled) 1f else 0.5f
        }
    }
}
