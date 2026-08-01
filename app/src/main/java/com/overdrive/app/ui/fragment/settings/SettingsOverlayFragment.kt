package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.roadsense.config.RoadSenseConfig
import com.overdrive.app.roadsense.overlay.RoadSenseOverlayService
import org.json.JSONObject

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
    private var roadSenseMasterOn = false
    private var applyingRoadSenseConfig = false

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

        swCamera.setOnCheckedChangeListener { _, checked -> persist("cameraVisible", checked) }
        swReplay.setOnCheckedChangeListener { _, checked -> persist("replayVisible", checked) }
        swTrip.setOnCheckedChangeListener { _, checked -> persist("tripVisible", checked) }
        swRoadSense.setOnCheckedChangeListener { _, checked ->
            if (!applyingRoadSenseConfig) persistRoadSense(checked)
        }
    }

    override fun onResume() {
        super.onResume()
        // This preference is also exposed on the RoadSense page. Refresh when the
        // user returns so both entry points always display the same stored value.
        refreshRoadSenseSwitch(forceReload = true)
    }

    override fun onDestroyView() {
        roadSenseSwitch = null
        roadSenseRow = null
        super.onDestroyView()
    }

    /**
     * Persist the flag and immediately nudge the overlay service so the
     * toggle takes effect now instead of on the next 3-10s poll tick.
     * StatusOverlayService.onStartCommand re-uses the existing instance
     * and cancels any in-flight delayed poll, firing one synchronously.
     */
    private fun persist(key: String, value: Boolean) {
        UnifiedConfigManager.setStatusOverlay(JSONObject().put(key, value))
        context?.let { com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(it) }
    }

    private fun persistRoadSense(visible: Boolean) {
        // setChecked() fires the listener even on a disabled switch, so the
        // master gate has to be enforced here too, not just via row.isEnabled.
        if (!roadSenseMasterOn) {
            refreshRoadSenseSwitch(forceReload = false)
            return
        }
        if (RoadSenseConfig.setOverlayVisible(visible)) {
            context?.let { RoadSenseOverlayService.syncWithConfig(it) }
        } else {
            refreshRoadSenseSwitch(forceReload = true)
        }
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
