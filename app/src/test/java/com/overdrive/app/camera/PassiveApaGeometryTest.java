package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PassiveApaGeometryTest {
    @Test
    public void preservesNativeAspectAtStreamingWidths() {
        assertEquals(720, PassiveApaGeometry.heightForWidth(1280));
        assertEquals(540, PassiveApaGeometry.heightForWidth(960));
        assertEquals(450, PassiveApaGeometry.heightForWidth(800));
        assertEquals(360, PassiveApaGeometry.heightForWidth(640));
        assertEquals(270, PassiveApaGeometry.heightForWidth(480));
    }
}
