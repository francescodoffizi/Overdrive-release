package com.overdrive.app.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.condition.Conditions;
import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.EventData;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the DRIVER seat-occupancy wiring: the schema must accept {@code seat=driver}, and the
 * {@link EventData} it builds must be the SAME key {@link BydEvent} publishes on. A mismatch in
 * type or variables silently decouples trigger from publish — the rule loads, looks healthy and
 * never fires, which is the exact class of bug this feature was added to fix.
 *
 * <p>Goes through {@link Conditions} directly rather than {@code Automations.getCondition}: the
 * latter's static initialiser starts the real HAL pollers and touches the filesystem, so it
 * cannot initialise in a plain JVM unit test. {@code Conditions} is the same schema object that
 * initialiser would have built.
 */
public class DriverOccupantWiringTest {

    private static JSONObject occupant(String seat) throws Exception {
        return new JSONObject()
                .put("type", "occupant")
                .put("variables", new JSONObject().put("seat", seat));
    }

    private static EventCondition occupantCondition() {
        EventCondition c = new Conditions().getCondition("occupant");
        assertNotNull("occupant condition missing from the schema", c);
        return c;
    }

    /** seat=driver is a VALID schema option again — it was withdrawn, and is now inferred. */
    @Test
    public void driverSeatIsAValidSchemaOption() throws Exception {
        assertNotNull("schema rejected seat=driver",
                occupantCondition().eventData(occupant("driver")));
    }

    /** THE WIRING GUARD: schema-built key == published key, for both seats. */
    @Test
    public void schemaKeysMatchThePublishedEventKeys() throws Exception {
        EventCondition c = occupantCondition();
        assertEquals("driver key mismatch: a driver rule would never fire",
                BydEvent.OCCUPANT_DRIVER, c.eventData(occupant("driver")));
        assertEquals("passenger key mismatch",
                BydEvent.OCCUPANT_PASSENGER, c.eventData(occupant("passenger")));
    }

    /** The two seats must stay DISTINCT keys, else one seat's state clobbers the other's. */
    @Test
    public void driverAndPassengerAreDistinctKeys() {
        assertFalse("driver and passenger collapsed onto one state key",
                BydEvent.OCCUPANT_DRIVER.equals(BydEvent.OCCUPANT_PASSENGER));
    }

    /** An unknown seat is still rejected — the enum must not have been widened to free text. */
    @Test
    public void unknownSeatIsStillRejected() throws Exception {
        assertEquals(null, occupantCondition().eventData(occupant("rear-left")));
    }
}
