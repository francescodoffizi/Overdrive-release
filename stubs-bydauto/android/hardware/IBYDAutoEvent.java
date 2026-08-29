package android.hardware;

public interface IBYDAutoEvent {
    byte[] getBufferData();

    Object getData();

    int getDeviceType();

    double getDoubleValue();

    int getEventType();

    int getValue();

    void setData(Object obj);
}
