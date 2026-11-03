package android.hardware.bydauto.statistic;

/**
 * Compile-only declaration of the OEM BYD listener API.
 * This class is deliberately excluded from the APK. At runtime the class
 * must resolve to the vehicle's real framework/OEM implementation.
 */
public abstract class AbsBYDAutoStatisticListener {
    public AbsBYDAutoStatisticListener() {}
    public void onElecPercentageChanged(double percentage) {}
    public void onElecDrivingRangeChanged(int rangeKm) {}
    public void onEVRemainingBatteryPowerChanged(float remainingKwh) {}
    // Alternate callback names observed in the OverDrive reference APK.
    public void onRemainingBatteryPowerChanged(float remainingKwh) {}
    public void onRemainingBatteryPowerChanged(double remainingKwh) {}
}
