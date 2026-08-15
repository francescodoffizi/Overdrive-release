package com.overdrive.app.overlay;

/**
 * Resolves the vendor framework helper against the package AppOp.
 *
 * <p>Some BYD builds report {@code Settings.canDrawOverlays()} as granted for
 * compatibility-targeted apps even while SYSTEM_ALERT_WINDOW is explicitly
 * denied. A definitive AppOps result therefore wins; the framework value is
 * used only when AppOps cannot be queried.</p>
 */
public final class OverlayPermissionDecision {

    private OverlayPermissionDecision() {}

    public static boolean resolve(Boolean appOpsAllowed, boolean frameworkAllowed) {
        return appOpsAllowed != null ? appOpsAllowed.booleanValue() : frameworkAllowed;
    }
}
