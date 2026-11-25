package android.hardware.bydauto.radar;

public class AbsBYDAutoRadarListener {
    public void onRadarObstacleDistanceChanged(int area, int value) {}
    public void onRadarProbeStateChanged(int area, int state) {}
    public void onReverseRadarSwitchStateChanged(int state) {}
}
