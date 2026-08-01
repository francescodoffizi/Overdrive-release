package com.overdrive.app.automation;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * Pins {@link Automation#migrateStaleOptions} — the load-time rewrite hook that keeps
 * ALREADY-SAVED automations alive across an option withdrawal.
 *
 * <p>The lifecycle at stake: {@code disk → fromJson → memory → saveToFile → disk}. Anything
 * {@code fromJson} rejects is dropped from memory, and the next save rewrites the file without
 * it — permanent, silent deletion. So a withdrawn option's stored value must be REWRITTEN to a
 * faithful successor, never rejected.
 *
 * <p><b>Currently no option needs migrating.</b> The one historical rule
 * ({@code occupant seat=driver → passenger}) was RETIRED when the driver seat became a supported
 * option again — inferred from the seatbelt-reminder mask + driver belt. Rewriting it now would
 * send a freshly-authored driver rule to the wrong seat. These tests therefore pin the
 * pass-through contract: every entry survives, in order, with its variables untouched — which is
 * exactly what protects a driver-seat rule from the old rewrite.
 */
public class StaleOptionMigrationTest {

    private static JSONArray arr(String... entries) throws Exception {
        JSONArray a = new JSONArray();
        for (String e : entries) a.put(new JSONObject(e));
        return a;
    }

    private static String seatOf(JSONObject entry) {
        JSONObject v = entry.optJSONObject("variables");
        return v == null ? null : v.optString("seat");
    }

    /**
     * THE REGRESSION GUARD: an {@code occupant seat=driver} entry is a VALID rule again and must
     * pass through untouched. If the retired rewrite is ever reinstated, this fails — a driver
     * rule silently reading the front-passenger seat is precisely the bug that retirement fixed.
     */
    @Test
    public void driverOccupantEntryIsNoLongerRewritten() throws Exception {
        List<JSONObject> out = Automation.migrateStaleOptions(
                arr("{\"type\":\"occupant\",\"variables\":{\"seat\":\"driver\"}}"));

        assertEquals(1, out.size());
        assertEquals("driver", seatOf(out.get(0)));
    }

    /**
     * Both seats in one rule ("driving alone": driver occupied AND passenger empty) survive as
     * two DISTINCT terms. The old migration collapsed them onto one seat and then dropped one to
     * avoid the resulting contradiction; both halves must now be preserved.
     */
    @Test
    public void bothSeatsSurviveAsDistinctTerms() throws Exception {
        List<JSONObject> out = Automation.migrateStaleOptions(arr(
                "{\"type\":\"occupant\",\"variables\":{\"seat\":\"driver\"}}",
                "{\"type\":\"occupant\",\"variables\":{\"seat\":\"passenger\"}}"));

        assertEquals("no entry may be dropped", 2, out.size());
        assertEquals("driver", seatOf(out.get(0)));
        assertEquals("passenger", seatOf(out.get(1)));
    }

    /**
     * THE NEVER-EMPTY INVARIANT: migration must never shrink a non-empty list. An emptied
     * condition list means "always met" — the automation would fire unconditionally.
     */
    @Test
    public void migrationNeverEmptiesOrShrinksAList() throws Exception {
        String d = "{\"type\":\"occupant\",\"variables\":{\"seat\":\"driver\"}}";
        String p = "{\"type\":\"occupant\",\"variables\":{\"seat\":\"passenger\"}}";
        String[][] cases = {
                {d}, {p}, {d, d}, {d, p}, {p, d}, {p, p},
                {d, d, d}, {d, d, p}, {d, p, d}, {p, d, d}, {d, p, p}, {p, d, p}, {p, p, d},
        };
        for (String[] c : cases) {
            List<JSONObject> out = Automation.migrateStaleOptions(arr(c));
            assertEquals("migration changed the size of a " + c.length + "-entry list",
                    c.length, out.size());
        }
    }

    /** Other condition types pass through untouched and in order — even ones with a seat. */
    @Test
    public void otherConditionTypesAreNeverTouched() throws Exception {
        List<JSONObject> out = Automation.migrateStaleOptions(arr(
                "{\"type\":\"seatbelt\",\"variables\":{\"seat\":\"driver\"}}",
                "{\"type\":\"doorState\",\"variables\":{\"door\":\"driver\"}}",
                "{\"type\":\"occupant\",\"variables\":{\"seat\":\"driver\"}}"));

        assertEquals(3, out.size());
        assertEquals("seatbelt keeps its driver seat", "driver", seatOf(out.get(0)));
        assertEquals("doorState", out.get(1).optString("type"));
        assertEquals("occupant keeps its driver seat", "driver", seatOf(out.get(2)));
    }

    /** Malformed occupant entries pass through for the validator to judge — migration must
     *  neither crash nor invent values. */
    @Test
    public void malformedEntriesPassThroughUnchanged() throws Exception {
        List<JSONObject> out = Automation.migrateStaleOptions(arr(
                "{\"type\":\"occupant\"}",
                "{\"type\":\"occupant\",\"variables\":{}}",
                "{\"type\":\"occupant\",\"variables\":{\"seat\":7}}"));

        assertEquals(3, out.size());
        assertEquals(null, out.get(0).optJSONObject("variables"));
        assertEquals("", seatOf(out.get(1)));
        assertEquals("a non-string seat is not rewritten", "7", seatOf(out.get(2)));
    }

    /** A null array yields an empty list rather than throwing. */
    @Test
    public void nullArrayYieldsEmptyList() throws Exception {
        assertEquals(0, Automation.migrateStaleOptions((JSONArray) null).size());
    }

    /** Round-trip convergence: migrating already-migrated output changes nothing. */
    @Test
    public void migrationIsIdempotent() throws Exception {
        JSONArray first = new JSONArray();
        for (JSONObject o : Automation.migrateStaleOptions(arr(
                "{\"type\":\"occupant\",\"variables\":{\"seat\":\"driver\"}}",
                "{\"type\":\"speed\",\"variables\":{}}"))) {
            first.put(o);
        }
        List<JSONObject> second = Automation.migrateStaleOptions(first);

        assertEquals(first.length(), second.size());
        for (int i = 0; i < second.size(); i++) {
            assertEquals(first.getJSONObject(i).toString(), second.get(i).toString());
        }
    }
}
