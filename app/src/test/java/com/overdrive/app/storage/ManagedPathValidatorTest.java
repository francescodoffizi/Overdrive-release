package com.overdrive.app.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Managed-root containment semantics used by the API handler's path gates
 * (filename routes AND the index-resolved ID routes). Candidates and roots
 * are pre-canonicalized by the caller; these tests cover the containment
 * pitfalls that canonicalization alone does not solve.
 */
public class ManagedPathValidatorTest {

    private static final String SEP = File.separator;
    private static final String ROOT = SEP + "storage" + SEP + "emulated" + SEP + "0"
            + SEP + "Overdrive" + SEP + "recordings";
    private static final List<String> ROOTS = Collections.singletonList(ROOT);

    @Test
    public void fileInsideRootIsAllowed() {
        assertTrue(ManagedPathValidator.isUnderAnyRoot(ROOT + SEP + "cam_1.mp4", ROOTS));
    }

    @Test
    public void nestedFileInsideRootIsAllowed() {
        assertTrue(ManagedPathValidator.isUnderAnyRoot(
                ROOT + SEP + "sub" + SEP + "cam_1.mp4", ROOTS));
    }

    @Test
    public void prefixCollisionSiblingIsRejected() {
        // /…/recordings_backup shares the string prefix but is NOT inside
        // /…/recordings — the classic bare-startsWith bug.
        assertFalse(ManagedPathValidator.isUnderAnyRoot(
                ROOT + "_backup" + SEP + "cam_1.mp4", ROOTS));
    }

    @Test
    public void rootItselfIsRejected() {
        assertFalse(ManagedPathValidator.isUnderAnyRoot(ROOT, ROOTS));
        assertFalse(ManagedPathValidator.isUnderAnyRoot(ROOT + SEP, ROOTS));
    }

    @Test
    public void unrelatedAbsolutePathIsRejected() {
        assertFalse(ManagedPathValidator.isUnderAnyRoot(
                SEP + "data" + SEP + "local" + SEP + "tmp" + SEP + "overdrive_recordings_h2.mv.db",
                ROOTS));
    }

    @Test
    public void secondRootAlsoMatches() {
        String sdRoot = SEP + "storage" + SEP + "ABCD-1234" + SEP + "Overdrive"
                + SEP + "recordings";
        assertTrue(ManagedPathValidator.isUnderAnyRoot(
                sdRoot + SEP + "cam_1.mp4", Arrays.asList(ROOT, sdRoot)));
    }

    @Test
    public void rootWithTrailingSeparatorMatchesSameAsWithout() {
        assertTrue(ManagedPathValidator.isUnderAnyRoot(
                ROOT + SEP + "cam_1.mp4", Collections.singletonList(ROOT + SEP)));
    }

    @Test
    public void nullAndEmptyInputsAreRejected() {
        assertFalse(ManagedPathValidator.isUnderAnyRoot(null, ROOTS));
        assertFalse(ManagedPathValidator.isUnderAnyRoot("", ROOTS));
        assertFalse(ManagedPathValidator.isUnderAnyRoot(ROOT + SEP + "x.mp4", null));
        assertFalse(ManagedPathValidator.isUnderAnyRoot(
                ROOT + SEP + "x.mp4", Arrays.asList(null, "")));
    }
}
