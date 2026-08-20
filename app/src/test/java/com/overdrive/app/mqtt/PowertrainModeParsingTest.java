package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public class PowertrainModeParsingTest {

    @Test
    public void acceptsOnlyFieldValidatedWritablePowertrainModes() {
        assertEquals(Arrays.asList("ev", "hev"),
                VehicleControlCatalog.get("powertrain_mode").options);
        assertEquals(Integer.valueOf(1), VehicleControlCatalog.powertrainModeValue("ev"));
        assertEquals(Integer.valueOf(3), VehicleControlCatalog.powertrainModeValue("hev"));

        assertNull(VehicleControlCatalog.powertrainModeValue(null));
        assertNull(VehicleControlCatalog.powertrainModeValue(""));
        assertNull(VehicleControlCatalog.powertrainModeValue("force_ev"));
        assertNull(VehicleControlCatalog.powertrainModeValue("force-ev"));
        assertNull(VehicleControlCatalog.powertrainModeValue("fuel"));
        assertNull(VehicleControlCatalog.powertrainModeValue("keep"));
        assertNull(VehicleControlCatalog.powertrainModeValue("hybrid"));
        assertNull(VehicleControlCatalog.powertrainModeValue("0"));
        assertNull(VehicleControlCatalog.powertrainModeValue("1"));
    }
}
