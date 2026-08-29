package android.hardware.bydauto.doorlock;

/**
 * Stub for BYD DoorLock listener.
 * On real BYD devices this class is provided by the system framework.
 * 
 * From BYD SDK docs: callback invoked when the status of car's door lock has changed.
 * The registerListener method on BYDAutoDoorLockDevice expects this abstract class,
 * NOT the IBYDAutoListener interface (unlike bodywork/AC devices).
 */
public abstract class AbsBYDAutoDoorLockListener {

    /**
     * Callback method invoked when one specific door lock state has changed.
     *
     * @param area  The location of the lock (DOOR_LOCK_AREA_LEFT_FRONT=1, etc.)
     * @param state The door lock state: INVALID=0, UNLOCK=1, LOCK=2
     */
    public void onDoorLockStatusChanged(int area, int state) {
    }
}
