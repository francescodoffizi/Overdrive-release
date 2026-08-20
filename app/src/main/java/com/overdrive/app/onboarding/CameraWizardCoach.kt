package com.overdrive.app.onboarding

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.overdrive.app.R
import com.overdrive.app.launcher.AdbDaemonLauncher

/**
 * Coaches the owner through the REAL camera-mapping procedure on the live
 * dialog_camera_mapping.xml — it does NOT reimplement the dialog. Flow:
 *
 *   1. read the 360/pano preview (taught read-from-the-bottom: BL=rear, BR=left)
 *   2. read the dashcam (forward) preview via the candidate navigator
 *   3. PAIRING — spotlight the "Which camera is which?" card; the common fix is the
 *      one-tap Swap. "Pick IDs manually" expands the advanced section and falls back to
 *      the classic per-group walkthrough (rgManualCameraId + rgOemDashcamId)
 *   4. SAVE & RESTART — the wizard fires the full daemon restart so "save and restart"
 *      is one action; narrated ~10s determinate progress ("feed blinks, this is normal")
 *   5. VERIFY — "do the feeds look right?" with a Re-pick loop (non-destructive)
 *   6. OPTIONAL windshield/OEM-layout via spinnerCameraRole
 *
 * The overlay is re-parented onto the dialog's OWN window (a separate window from the
 * Activity content) so coachmarks sit above the MaterialAlertDialog. Touches inside the
 * spotlight cutout PASS THROUGH so the user drives the real controls; nothing here
 * auto-commits. A wrong pick is fully recoverable (every selector re-reads saved state).
 */
class CameraWizardCoach(
    private val activity: Activity,
    private val overlay: OnboardingOverlayView,
    private val state: OnboardingState,
    private val adbLauncher: AdbDaemonLauncher,
    private val onFinished: (Outcome) -> Unit,
) {
    enum class Outcome { SAVED, DEFERRED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dialogView: View? = null
    private var dialogDecor: ViewGroup? = null
    private var originalParent: ViewGroup? = null
    private var applyingAnim: android.animation.ValueAnimator? = null
    private var attached = false
    private var finished = false
    private var openFailsafe: Runnable? = null
    private var daemonWaitRunnable: Runnable? = null

    /** Sub-steps for resume + progress labelling. */
    private enum class Sub { INTRO, READ_PANO, READ_DASHCAM, PICK_IDS, APPLYING, VERIFY, WINDSHIELD }

    fun begin() {
        state.cameraStep = OnboardingState.CameraStep.IN_PROGRESS
        // Intro on the Activity content (dialog not open yet).
        overlay.consumeCutoutTouch = false
        overlay.showCentered()
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_intro_title),
            body = activity.getString(R.string.onboarding_camera_intro_body),
            primaryText = activity.getString(R.string.onboarding_camera_intro_primary),
            onPrimary = { openDialogThenCoach() },
            secondaryText = activity.getString(R.string.onboarding_later),
            onSecondary = { defer() },
            stepLabel = stepLabel(Sub.INTRO),
        )
    }

    /**
     * On first launch the camera daemon can still be starting (the preview HTTP server
     * isn't up yet), so opening the dialog immediately would fail the fetch. Show a
     * loader and POLL until the daemon answers (bounded), THEN open the dialog. The loader
     * always carries a Cancel so the user is never stuck waiting.
     */
    private fun openDialogThenCoach() {
        val ma = activity as? com.overdrive.app.ui.MainActivity
        if (ma == null) { defer(); return }
        waitForDaemonThenOpen(ma, DAEMON_WAIT_ATTEMPTS)
    }

    private fun waitForDaemonThenOpen(ma: com.overdrive.app.ui.MainActivity, remaining: Int) {
        if (finished) return
        overlay.consumeCutoutTouch = false
        overlay.showCentered()
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_opening_title),
            body = activity.getString(R.string.onboarding_camera_opening_body),
            primaryText = null, onPrimary = null,
            secondaryText = activity.getString(R.string.onboarding_later),
            onSecondary = { stopWaiting(); restoreOverlayToContent(); defer() },
        )
        // Indeterminate-feel determinate bar: fill across the wait window.
        val pct = (((DAEMON_WAIT_ATTEMPTS - remaining).toFloat() / DAEMON_WAIT_ATTEMPTS) * 100).toInt()
        overlay.setProgress(pct.coerceIn(5, 95))
        adbLauncher.isDaemonRunning { running ->
            mainHandler.post {
                if (finished) return@post
                if (running) {
                    overlay.setProgress(null)
                    actuallyOpenDialog(ma)
                } else if (remaining <= 0) {
                    // Daemon still not up — try opening anyway (the fetch has its own
                    // timeout) and let the open-failsafe defer if it never shows.
                    overlay.setProgress(null)
                    actuallyOpenDialog(ma)
                } else {
                    val r = Runnable { waitForDaemonThenOpen(ma, remaining - 1) }
                    daemonWaitRunnable = r
                    mainHandler.postDelayed(r, DAEMON_WAIT_INTERVAL_MS)
                }
            }
        }
    }

    private fun stopWaiting() {
        daemonWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        daemonWaitRunnable = null
    }

    private fun actuallyOpenDialog(ma: com.overdrive.app.ui.MainActivity) {
        stopWaiting()
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_opening_title),
            body = activity.getString(R.string.onboarding_camera_opening_body),
            primaryText = null, onPrimary = null,
            // A Cancel escape so the user is never trapped on a button-less card even if
            // the failsafe timing is unlucky.
            secondaryText = activity.getString(R.string.onboarding_later),
            onSecondary = { restoreOverlayToContent(); defer() },
        )
        // FAILSAFE: the dialog only appears after an async fetch (~3–7s) that can FAIL
        // (e.g. daemon still relaunching right after Step-0 auth) — in which case
        // showCameraMappingDialog never runs and attachToDialog is never called. Without
        // this, the button-less "opening…" card would strand the user until ACC-ON.
        // Mirrors the Step-0 auth failsafe (OnboardingHost.renderAuthorize).
        openFailsafe = Runnable {
            if (!attached && !finished) {
                Log.w(TAG, "camera dialog never opened (fetch failed?) — deferring")
                // Drop the pending coach so a LATE-succeeding fetch can't re-attach us
                // onto the next chapter's overlay (attachToDialog also guards on finished).
                (activity as? com.overdrive.app.ui.MainActivity)?.clearPendingCameraOnboardingCoach()
                restoreOverlayToContent()
                defer()
            }
        }
        mainHandler.postDelayed(openFailsafe!!, OPEN_FAILSAFE_MS)
        // MainActivity will invoke attachToDialog(dialogView, decorView) on show().
        ma.openCameraMappingForOnboarding(this)
    }

    /**
     * Called by MainActivity right after the camera dialog's show(). Re-parents the
     * overlay onto the dialog window so coachmarks sit above it, then starts the strip
     * coaching. [decor] is dialog.window.decorView; [view] is the inflated dialog content.
     */
    fun attachToDialog(view: View, decor: ViewGroup) {
        // If this coach already finished (the open-failsafe fired and deferred us to the
        // vehicle chapter, or ACC-on cancelled us), a LATE-succeeding fetch must NOT
        // re-attach: the overlay now belongs to the next chapter, and stealing it back
        // onto an abandoned camera dialog visibly breaks that chapter. Bail inert.
        if (finished) return
        attached = true
        openFailsafe?.let { mainHandler.removeCallbacks(it) }
        openFailsafe = null
        dialogView = view
        dialogDecor = decor
        // Move the overlay from Activity content onto the dialog's decor view.
        (overlay.parent as? ViewGroup)?.let { p -> originalParent = p; p.removeView(overlay) }
        decor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Give the dialog a beat to lay out so anchor bounds are real.
        overlay.post { coachReadPano() }
    }

    /** MainActivity calls this if the dialog was dismissed out from under us. */
    fun onDialogDismissed() {
        // If we didn't reach a SAVED outcome, treat as deferred so it re-offers later.
        if (state.cameraStep != OnboardingState.CameraStep.SAVED_OK) {
            restoreOverlayToContent()
            defer()
        }
    }

    // ---- coaching steps (anchored on the live dialog controls) -----------------------

    private fun coachReadPano() {
        val v = dialogView ?: return
        val preview = v.findViewById<View>(R.id.ivCameraCandidatePreview) ?: return spotlightFallback()
        overlay.consumeCutoutTouch = false  // let the user scrub Prev/Next underneath
        overlay.spotlight(preview, animate = false)
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_pano_title),
            body = activity.getString(R.string.onboarding_camera_pano_body),
            primaryText = activity.getString(R.string.onboarding_next),
            onPrimary = { coachReadDashcam() },
            secondaryText = activity.getString(R.string.onboarding_later),
            onSecondary = { restoreOverlayToContent(); defer() },
            stepLabel = stepLabel(Sub.READ_PANO),
        )
    }

    private fun coachReadDashcam() {
        val v = dialogView ?: return
        val preview = v.findViewById<View>(R.id.ivCameraCandidatePreview) ?: return spotlightFallback()
        val nav = v.findViewById<View>(R.id.btnNextCandidate)
        overlay.spotlight(nav ?: preview)
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_dashcam_title),
            body = activity.getString(R.string.onboarding_camera_dashcam_body),
            primaryText = activity.getString(R.string.onboarding_next),
            onPrimary = { coachPairing() },
            secondaryText = activity.getString(R.string.onboarding_back),
            onSecondary = { coachReadPano() },
            stepLabel = stepLabel(Sub.READ_DASHCAM),
        )
    }

    /**
     * Pairing-first step: spotlight the "Which camera is which?" card and
     * teach the one-tap Swap. Primary advances straight to save & restart
     * (Swap already saved if the user tapped it — re-saving is idempotent);
     * secondary expands the advanced ID pickers and falls back to the
     * classic per-group walkthrough. On layouts without the pairing card
     * (dialog rendered from an older APK layout) this step degrades to the
     * classic flow immediately, preserving the pre-pairing wizard.
     */
    private fun coachPairing() {
        val v = dialogView ?: return
        val pairingCard = v.findViewById<View>(R.id.cardCameraPairing)
        if (pairingCard == null) {
            // Legacy layout — keep the original two-radio-group walkthrough.
            coachPickIds()
            return
        }
        overlay.consumeCutoutTouch = false  // let the user tap Swap underneath
        overlay.spotlight(pairingCard)
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_pair_title),
            body = activity.getString(R.string.onboarding_camera_pair_body),
            primaryText = activity.getString(R.string.onboarding_camera_pair_primary),
            onPrimary = { coachSaveAndRestart() },
            secondaryText = activity.getString(R.string.onboarding_camera_pair_secondary),
            onSecondary = {
                ensureAdvancedIdsVisible(v)
                coachPickIds()
            },
            stepLabel = stepLabel(Sub.PICK_IDS),
        )
    }

    /**
     * The manual ID pickers live behind a collapsed "advanced" expander.
     * Expand via the real toggle (keeps its chevron state in sync) before
     * spotlighting anything inside; no-op on layouts without the expander.
     */
    private fun ensureAdvancedIdsVisible(v: View) {
        val section = v.findViewById<View>(R.id.advancedIdSection) ?: return
        if (section.visibility != View.VISIBLE) {
            val toggle = v.findViewById<View>(R.id.btnToggleAdvancedIds)
            if (toggle != null) toggle.performClick() else section.visibility = View.VISIBLE
        }
    }

    private fun coachPickIds() {
        val v = dialogView ?: return
        ensureAdvancedIdsVisible(v)
        val panoGroup = v.findViewById<View>(R.id.rgManualCameraId)
        overlay.spotlight(panoGroup ?: v)
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_pick_title),
            body = activity.getString(R.string.onboarding_camera_pick_body),
            primaryText = activity.getString(R.string.onboarding_camera_pick_primary),
            onPrimary = {
                // Spotlight the dashcam group next, then move to save&restart.
                val dashGroup = v.findViewById<View>(R.id.rgOemDashcamId)
                if (dashGroup != null) {
                    overlay.spotlight(dashGroup)
                    overlay.bindStep(
                        title = activity.getString(R.string.onboarding_camera_pick_dash_title),
                        body = activity.getString(R.string.onboarding_camera_pick_dash_body),
                        primaryText = activity.getString(R.string.onboarding_camera_save_primary),
                        onPrimary = { coachSaveAndRestart() },
                        secondaryText = activity.getString(R.string.onboarding_back),
                        onSecondary = { coachPairing() },
                        stepLabel = stepLabel(Sub.PICK_IDS),
                    )
                } else {
                    coachSaveAndRestart()
                }
            },
            secondaryText = activity.getString(R.string.onboarding_back),
            onSecondary = { coachPairing() },
            stepLabel = stepLabel(Sub.PICK_IDS),
        )
    }

    private fun coachSaveAndRestart() {
        // Drive the real saves on the dialog, then fire the full daemon restart so the
        // pano id (which alone does NOT restart) actually goes live now.
        val v = dialogView ?: return
        v.findViewById<View>(R.id.btnSaveManualCameraId)?.performClick()
        v.findViewById<View>(R.id.btnSaveOemDashcamId)?.performClick()

        overlay.consumeCutoutTouch = false
        overlay.showCentered()
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_applying_title),
            body = activity.getString(R.string.onboarding_camera_applying_body),
            primaryText = null, onPrimary = null,
            stepLabel = stepLabel(Sub.APPLYING),
        )
        // Ask MainActivity to run the real ~10s restart; narrate a determinate bar.
        val ma = activity as? com.overdrive.app.ui.MainActivity
        ma?.restartCameraDaemonForOnboarding()
        animateApplyingProgress {
            // Restart window elapsed → verify.
            coachVerify()
        }
    }

    private fun animateApplyingProgress(onDone: () -> Unit) {
        applyingAnim?.cancel()
        applyingAnim = android.animation.ValueAnimator.ofInt(0, 100).apply {
            duration = RESTART_NARRATION_MS
            interpolator = OnboardingMotion.DECELERATE
            addUpdateListener {
                // Don't paint into a detached overlay (ACC-on tore it off mid-restart).
                if (overlay.isAttachedToWindow) overlay.setProgress(it.animatedValue as Int)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    applyingAnim = null
                    if (finished || !overlay.isAttachedToWindow) return  // dismissed mid-restart
                    overlay.setProgress(null); onDone()
                }
            })
            start()
        }
    }

    /**
     * Hard teardown invoked by the host on an ACC-on edge (or any forced dismiss) so the
     * ~10s applying animator can't keep running detached, holding the coach + Activity.
     */
    fun cancel() {
        finished = true
        applyingAnim?.cancel(); applyingAnim = null
        openFailsafe?.let { mainHandler.removeCallbacks(it) }; openFailsafe = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun coachVerify() {
        // The dialog may have been dismissed by a role-save earlier; re-anchor on the
        // preview if it's still there, else show a centered verify card.
        val preview = dialogView?.findViewById<View>(R.id.ivCameraCandidatePreview)
        if (preview != null) overlay.spotlight(preview) else overlay.showCentered()
        overlay.pulseAttention()
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_verify_title),
            body = activity.getString(R.string.onboarding_camera_verify_body),
            primaryText = activity.getString(R.string.onboarding_camera_verify_ok),
            onPrimary = { offerWindshield() },
            secondaryText = activity.getString(R.string.onboarding_camera_verify_repick),
            onSecondary = { coachReadPano() },
            stepLabel = stepLabel(Sub.VERIFY),
        )
    }

    private fun offerWindshield() {
        if (state.windshieldStepDone) { finishSaved(); return }
        val role = dialogView?.findViewById<View>(R.id.spinnerCameraRole)
        if (role != null) overlay.spotlight(role) else overlay.showCentered()
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_camera_windshield_title),
            body = activity.getString(R.string.onboarding_camera_windshield_body),
            primaryText = activity.getString(R.string.onboarding_camera_windshield_primary),
            onPrimary = {
                state.windshieldStepDone = true
                // Leave the user on the role control to pick + Save themselves; advance.
                finishSaved()
            },
            secondaryText = activity.getString(R.string.onboarding_camera_windshield_skip),
            onSecondary = { state.windshieldStepDone = true; finishSaved() },
            stepLabel = stepLabel(Sub.WINDSHIELD),
        )
    }

    // ---- terminal --------------------------------------------------------------------

    private fun finishSaved() {
        if (finished) return
        finished = true
        applyingAnim?.cancel(); applyingAnim = null
        state.cameraStep = OnboardingState.CameraStep.SAVED_OK
        restoreOverlayToContent()
        onFinished(Outcome.SAVED)
    }

    private fun defer() {
        if (finished) return
        finished = true
        applyingAnim?.cancel(); applyingAnim = null
        state.cameraStep = OnboardingState.CameraStep.DEFERRED
        onFinished(Outcome.DEFERRED)
    }

    /** Spotlight target missing — degrade to a centered card rather than point at nothing. */
    private fun spotlightFallback() {
        overlay.showCentered()
    }

    /** Move the overlay back onto Activity content (off the dialog window) before exit. */
    private fun restoreOverlayToContent() {
        val decor = dialogDecor ?: return
        (overlay.parent as? ViewGroup)?.takeIf { it === decor }?.removeView(overlay)
        val back = originalParent ?: activity.findViewById(android.R.id.content)
        if (overlay.parent == null) {
            back.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        dialogDecor = null; dialogView = null
    }

    private fun stepLabel(sub: Sub): CharSequence =
        activity.getString(R.string.onboarding_camera_step_label, sub.ordinal + 1, Sub.values().size)

    private companion object {
        const val TAG = "CameraWizardCoach"
        // Mirrors restartCameraDaemonForCameraSettings' ~10s chain (kill + 5s HAL + 5s).
        const val RESTART_NARRATION_MS = 10000L
        // Async camera-state fetch is connect=3s + read=4s, plus the dialog inflate; the
        // failsafe must sit ABOVE that worst case so it never pre-empts a still-valid
        // in-flight open (the daemon-wait loader already runs first, so the daemon is
        // usually up before we even fetch).
        const val OPEN_FAILSAFE_MS = 12000L
        // First-launch daemon-warming wait: poll up to ~15s (30 × 500ms) before opening.
        const val DAEMON_WAIT_ATTEMPTS = 30
        const val DAEMON_WAIT_INTERVAL_MS = 500L
    }
}
