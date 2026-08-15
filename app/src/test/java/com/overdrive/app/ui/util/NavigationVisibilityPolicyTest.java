package com.overdrive.app.ui.util;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public class NavigationVisibilityPolicyTest {
    private final Set<String> known =
            new LinkedHashSet<>(Arrays.asList("live", "recordings", "trips"));

    @Test
    public void missingPreference_preservesCurrentCatalog() {
        assertEquals(known, NavigationVisibilityPolicy.resolve(null, known));
    }

    @Test
    public void unknownAndRemovedKeys_areIgnored() {
        Set<String> stored =
                new LinkedHashSet<>(Arrays.asList("recordings", "removed_destination"));
        assertEquals(
                Collections.singleton("recordings"),
                NavigationVisibilityPolicy.resolve(stored, known)
        );
    }

    @Test
    public void visibilityUpdate_cannotPersistUnknownKeys() {
        Set<String> current = new LinkedHashSet<>(Arrays.asList("live", "unknown"));
        assertEquals(
                Collections.singleton("live"),
                NavigationVisibilityPolicy.setVisible(current, "unknown", true, known)
        );
    }

    @Test
    public void visibilityUpdate_addsAndRemovesKnownKeys() {
        Set<String> current = new LinkedHashSet<>(Arrays.asList("live", "recordings"));
        Set<String> hidden =
                NavigationVisibilityPolicy.setVisible(current, "live", false, known);
        assertEquals(Collections.singleton("recordings"), hidden);

        Set<String> restored =
                NavigationVisibilityPolicy.setVisible(hidden, "trips", true, known);
        assertEquals(
                new LinkedHashSet<>(Arrays.asList("recordings", "trips")),
                restored
        );
    }
}
