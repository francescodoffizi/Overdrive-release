package com.overdrive.app.telemetry;

/**
 * Immutable value object holding a single point-in-time telemetry reading.
 * Thread-safe by design — all fields are final.
 */
public class TelemetrySnapshot {

    public final int speedKmh;
    public final int accelPedalPercent;     // 0-100
    public final int brakePedalPercent;     // 0-100
    public final boolean brakePedalPressed;
    public final int gearMode;              // 1-6 matching BYDAutoGearboxDevice constants
    public final boolean leftTurnSignal;
    public final boolean rightTurnSignal;
    public final boolean[] seatbeltBuckled; // indexed by seat position
    public final long timestampMs;

    public TelemetrySnapshot(int speedKmh, int accelPedalPercent, int brakePedalPercent,
                             boolean brakePedalPressed, int gearMode,
                             boolean leftTurnSignal, boolean rightTurnSignal,
                             boolean[] seatbeltBuckled, long timestampMs) {
        this.speedKmh = speedKmh;
        this.accelPedalPercent = accelPedalPercent;
        this.brakePedalPercent = brakePedalPercent;
        this.brakePedalPressed = brakePedalPressed;
        this.gearMode = gearMode;
        this.leftTurnSignal = leftTurnSignal;
        this.rightTurnSignal = rightTurnSignal;
        // Defensive copy to preserve immutability
        this.seatbeltBuckled = seatbeltBuckled != null ? seatbeltBuckled.clone() : new boolean[0];
        this.timestampMs = timestampMs;
    }

    /**
     * Maps gear mode constant to display character.
     * 1→'P', 2→'R', 3→'N', 4→'D', 5→'M', 6→'S', default→'?'
     */
    public char getGearChar() {
        switch (gearMode) {
            case 1: return 'P';
            case 2: return 'R';
            case 3: return 'N';
            case 4: return 'D';
            case 5: return 'M';
            case 6: return 'S';
            default: return '?';
        }
    }

    /**
     * Returns the color for the current gear mode (dark colors for white background).
     * R→red, D→green, P→gray, N→blue, S→orange, M→purple, others→black
     */
    public int getGearColor() {
        switch (gearMode) {
            case 1: return 0xFF666666; // P → dark gray
            case 2: return 0xFFCC0000; // R → dark red
            case 3: return 0xFF0066CC; // N → blue
            case 4: return 0xFF008800; // D → dark green
            case 5: return 0xFF8800CC; // M → purple
            case 6: return 0xFFCC6600; // S → orange
            default: return 0xFF000000; // unknown → black
        }
    }

    /**
     * Creates a default snapshot with safe values:
     * speed=0, gear=P, signals off, belts buckled.
     */
    public static TelemetrySnapshot createDefault() {
        return new TelemetrySnapshot(
                0,                          // speedKmh
                0,                          // accelPedalPercent
                0,                          // brakePedalPercent
                false,                      // brakePedalPressed
                1,                          // gearMode = P
                false,                      // leftTurnSignal
                false,                      // rightTurnSignal
                // seatbeltBuckled — NOT buckled-by-default. This snapshot is what the overlay
                // renders in the window between startPolling() and the first published poll, so
                // `true` painted a green ALL-CLEAR on a safety glyph from zero real data (for up
                // to one worker frame, burned into the recording). Matches
                // TelemetryDataCollector.lastSeatbelts, which is false/false for the same reason.
                new boolean[]{false, false},
                System.currentTimeMillis()  // timestampMs
        );
    }
}
