package com.overdrive.app.storage;

import java.io.File;

/**
 * Pure managed-root containment check for media file paths.
 *
 * <p>Extracted so the prefix semantics are unit-testable (audit: no focused
 * tests covered managed-path rejection). Callers are responsible for
 * CANONICALIZING both sides first ({@link File#getCanonicalPath()}) — that is
 * what defeats symlink traversal and the multiple mount aliases these volumes
 * are reachable through (/storage/emulated/0 vs /sdcard vs FUSE bridges).
 * This class then guards the remaining pitfalls:
 *
 * <ul>
 *   <li>Prefix collision: {@code /a/recordings-evil} must NOT match root
 *       {@code /a/recordings} — comparison is against
 *       {@code root + File.separator}, never a bare {@code startsWith}.
 *   <li>Root itself: the root directory is not a valid media file path.
 * </ul>
 */
public final class ManagedPathValidator {

    private ManagedPathValidator() {}

    /**
     * True iff {@code candidateCanonicalPath} is strictly INSIDE any of
     * {@code canonicalRoots}. Null/empty candidate or roots are rejected.
     */
    public static boolean isUnderAnyRoot(String candidateCanonicalPath,
                                         Iterable<String> canonicalRoots) {
        if (candidateCanonicalPath == null || candidateCanonicalPath.isEmpty()
                || canonicalRoots == null) {
            return false;
        }
        for (String root : canonicalRoots) {
            if (root == null || root.isEmpty()) continue;
            String prefix = root.endsWith(File.separator) ? root : root + File.separator;
            if (candidateCanonicalPath.startsWith(prefix)
                    && candidateCanonicalPath.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }
}
