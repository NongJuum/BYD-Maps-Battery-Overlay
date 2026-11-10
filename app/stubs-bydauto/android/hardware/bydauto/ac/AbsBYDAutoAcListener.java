package android.hardware.bydauto.ac;

public abstract class AbsBYDAutoAcListener {
    public void onTemperatureChanged(int area, int temp) {}
    public void onAcStarted() {}
    public void onAcStoped() {}
    public void onAcWindLevelChanged(int level) {}
}
