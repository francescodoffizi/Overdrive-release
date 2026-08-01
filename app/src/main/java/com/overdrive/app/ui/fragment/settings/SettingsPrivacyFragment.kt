package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.overdrive.app.R
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.util.RecordingScanner
import com.overdrive.app.ui.util.RecordingsApiClient
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Settings → Privacy & data pane.
 *
 * Hosts the on-device privacy stance, a live local-storage summary
 * (clip count + total size), and the destructive reset action.
 *
 * The reset button delegates to [MainActivity.invokeResetDataDialog],
 * preserving the exact behaviour of the legacy portrait "Reset data"
 * card.
 *
 * Storage values are populated by querying [RecordingScanner] (which is
 * already cached for ~5 seconds and dedupes across SD/internal). On any
 * exception the labels gracefully fall back to "Unavailable" so a
 * scanner regression never blanks the page.
 */
class SettingsPrivacyFragment : Fragment() {

    /**
     * Single-thread executor for the storage scan. Off-main is mandatory:
     * StorageManager's first-init in the UI process forks shell processes
     * (`sm list-volumes`, /proc/mounts probes, sleep loops to await SD-card
     * mount). On the main thread that's an ANR — which the user perceives
     * as a crash when they tap the Privacy & data row.
     */
    private var scanExecutor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_privacy, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.btnResetData).setOnClickListener {
            (activity as? MainActivity)?.invokeResetDataDialog()
        }
        populateStorage(view)
    }

    override fun onResume() {
        super.onResume()
        // Re-populate on resume: the user may have deleted clips on the
        // recordings page and come back here expecting the totals to
        // reflect that.
        view?.let { populateStorage(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanExecutor?.shutdownNow()
        scanExecutor = null
    }

    private fun populateStorage(root: View) {
        val tvClips = root.findViewById<TextView>(R.id.tvPrivacyClipsValue) ?: return
        val tvSize = root.findViewById<TextView>(R.id.tvPrivacySizeValue) ?: return
        val ctx = context?.applicationContext ?: return

        val executor = scanExecutor
            ?: Executors.newSingleThreadExecutor { r ->
                Thread(r, "PrivacyStorageScan").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }.also { scanExecutor = it }

        executor.execute {
            val result: Pair<Int, Long>? = try {
                // Ask the daemon first so we can tell "index down" (counts
                // unknown) apart from "genuinely no clips". scanRecordings()
                // returns an empty list in BOTH cases, which would render an
                // authoritative "0 clips · 0 B" while recordings sit on disk.
                if (RecordingsApiClient.fetchStats()?.indexUnavailable == true) {
                    null
                } else {
                    val all = RecordingScanner.scanRecordings(ctx)
                    all.size to all.sumOf { it.sizeBytes }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Storage scan failed: ${t.message}")
                null
            }
            mainHandler.post {
                if (!isAdded || view == null) return@post
                if (result == null) {
                    tvClips.setText(R.string.settings_privacy_storage_unavailable)
                    tvSize.setText(R.string.settings_privacy_storage_unavailable)
                } else {
                    val (count, totalBytes) = result
                    tvClips.text = formatClipCount(count)
                    tvSize.text = formatSize(totalBytes)
                }
            }
        }
    }

    private fun formatClipCount(count: Int): String {
        val res = if (count == 1) {
            R.string.settings_privacy_storage_count_format
        } else {
            R.string.settings_privacy_storage_count_format_plural
        }
        return getString(res, count)
    }

    /**
     * Compact "B / KB / MB / GB / TB" formatter — same conventions used
     * elsewhere in the app (storage screen, recording library footer).
     * Intentionally locale-agnostic for the unit suffix; the number gets
     * locale-formatted via [String.format] with the default locale.
     */
    private fun formatSize(bytes: Long): String {
        if (bytes < 0L) return getString(R.string.settings_privacy_storage_unavailable)
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        if (gb < 1024.0) return String.format(Locale.getDefault(), "%.2f GB", gb)
        val tb = gb / 1024.0
        return String.format(Locale.getDefault(), "%.2f TB", tb)
    }

    private companion object {
        const val TAG = "SettingsPrivacy"
    }
}
