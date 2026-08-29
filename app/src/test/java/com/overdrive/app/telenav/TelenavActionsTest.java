package com.overdrive.app.telenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.telenav.app.external.constants.FavoriteType;
import org.junit.Test;

public class TelenavActionsTest {

    @Test
    public void validatesCoordinatesAndRestrictsWritableFavoriteType() {
        TelenavActions.validateCoordinates(0.0, 0.0);
        assertEquals(
                FavoriteType.Normal,
                TelenavActions.buildPlace("Place", 1.0, 2.0, "Home", null, null)
                        .getFavoriteType());

        try {
            TelenavActions.validateCoordinates(91.0, 0.0);
            fail("latitude outside the valid range must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
