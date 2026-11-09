package com.byd.mapsoverlay;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs through app_process as ADB shell (UID 2000) for native DiLink 3 telemetry.
 * Publishes read-only BYD telemetry and fast ADAS/Light/Cluster BSD events over loopback HTTP.
 */
public final class BydTelemetryDaemon {
    private static final String TAG = "BydTelemetryDaemon";
    private static final int PORT = 19890;
    private static volatile JSONObject snapshot = unavailable("Starting");

    private static volatile double callbackSoc = -1.0;
    private static volatile int callbackRangeKm = -1;
    private static volatile float callbackRemainKwh = -1.0f;

    // Fast ADAS / Turn Light / Cluster Buzzle states
    private static volatile int fastBsdState = 0;
    private static volatile int fastDowWarnLeft = 0;
    private static volatile int fastDowWarnRight = 0;
    private static volatile int fastLeftMirrorStatus = 0;
    private static volatile int fastRightMirrorStatus = 0;
    private static volatile boolean fastLeftTurn = false;
    private static volatile boolean fastRightTurn = false;
    private static volatile boolean fastLeftDanger = false;
    private static volatile boolean fastRightDanger = false;
    private static volatile int fastTurnFlashState = 0;
    private static volatile int fastTurnLightState = 0;
    private static volatile int fastDoubleFlashState = 0;
    private static volatile int fastClusterBuzzleState = 0;
    private static volatile int fastClusterDashboardAlarm = 0;
    private static volatile int fastClusterSoundFreq = 0;
    private static volatile int fastClusterSoundType = 0;
    private static volatile boolean fastBsdAlertActive = false;

    private static volatile Object registeredStatisticDevice;

    private static final Pattern BMS_LOG_PATTERN = Pattern.compile(
            "SOC=([0-9.]+)%\\s+12V=([0-9.]+)V\\s+Temp=([0-9.]+)/([0-9.]+)/([0-9.]+).*?CellV=([0-9.]+)/([0-9.]+)V"
    );

    private static final AbsBYDAutoStatisticListener STATISTIC_LISTENER =
            new AbsBYDAutoStatisticListener() {
        @Override public void onElecPercentageChanged(double percentage) {
            if (percentage >= 0.0 && percentage <= 100.0) {
                callbackSoc = percentage;
            }
        }
        @Override public void onElecDrivingRangeChanged(int rangeKm) {
            if (rangeKm >= 0 && rangeKm <= 1500) {
                callbackRangeKm = rangeKm;
            }
        }
        @Override public void onEVRemainingBatteryPowerChanged(float remainingKwh) {
            acceptRemainingEnergy(remainingKwh);
        }
        public void onRemainingBatteryPowerChanged(float remainingKwh) {
            acceptRemainingEnergy(remainingKwh);
        }
        public void onRemainingBatteryPowerChanged(double remainingKwh) {
            acceptRemainingEnergy((float) remainingKwh);
        }
        private void acceptRemainingEnergy(float remainingKwh) {
            if (remainingKwh >= 0.0f && remainingKwh <= 200.0f) {
                callbackRemainKwh = remainingKwh;
            }
        }
    };

    private BydTelemetryDaemon() {}

    public static void main(String[] args) {
        log("Starting; uid=" + android.os.Process.myUid());
        try {
            String targetPkg = (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty())
                    ? args[0] : "com.byd.mapsoverlay.beta";
            Context context = createBydContext(targetPkg);
            if (context == null) throw new IllegalStateException("Could not create BYD package context");

            Thread poller = new Thread(() -> pollLoop(context), "BydTelemetryPoller");
            poller.setDaemon(true);
            poller.start();

            Thread fastSafetyPoller = new Thread(() -> safetyPollLoop(context), "BydSafetyWatcher");
            fastSafetyPoller.setDaemon(true);
            fastSafetyPoller.start();

            serveLoop();
        } catch (Throwable t) {
            log("FATAL: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static Context createBydContext(String pkgName) throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = null;

        try {
            thread = at.getMethod("currentActivityThread").invoke(null);
        } catch (Throwable ignored) {}

        if (thread == null) {
            final Object[] result = new Object[1];
            Thread bootstrap = new Thread(() -> {
                try {
                    result[0] = at.getMethod("systemMain").invoke(null);
                } catch (Throwable t) {
                    log("ActivityThread.systemMain failed: " + t);
                }
            }, "BydActivityThreadBootstrap");
            bootstrap.setDaemon(true);
            bootstrap.start();
            bootstrap.join(10_000L);
            if (bootstrap.isAlive()) {
                bootstrap.interrupt();
                log("ActivityThread.systemMain timed out");
            } else {
                thread = result[0];
            }
        }

        if (thread == null) {
            try {
                try { android.os.Looper.prepareMainLooper(); } catch (Throwable ignored) {}
                java.lang.reflect.Constructor<?> ctor = at.getDeclaredConstructor();
                ctor.setAccessible(true);
                thread = ctor.newInstance();
                try {
                    java.lang.reflect.Field current = at.getDeclaredField("sCurrentActivityThread");
                    current.setAccessible(true);
                    current.set(null, thread);
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                log("Manual ActivityThread fallback failed: " + t);
            }
        }

        if (thread == null) throw new IllegalStateException("ActivityThread unavailable");
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        if (system == null) throw new IllegalStateException("system context is null");

        Context app;
        try {
            app = system.createPackageContext(
                    pkgName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            try {
                app = system.createPackageContext(
                        "com.byd.mapsoverlay.beta",
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable t2) {
                app = system.createPackageContext(
                        "com.byd.mapsoverlay",
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            }
        }
        return new PermissionBypassContext(app);
    }

    private static final class PermissionBypassContext extends ContextWrapper {
        PermissionBypassContext(Context base) { super(base); }
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public int checkCallingOrSelfPermission(String permission) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
        @Override public int checkPermission(String permission, int pid, int uid) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
        @Override public int checkSelfPermission(String permission) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
        @Override public Context getApplicationContext() {
            Context app = super.getApplicationContext();
            return app != null ? app : this;
        }
    }

    /**
     * Fast 250ms watcher for ADAS BSD, Directional Danger (DOW / Mirrors), Turn Signals, and Cluster Alarms.
     * Runs as UID 2000 so BYD hardware permissions are never blocked.
     */
    private static void safetyPollLoop(Context context) {
        Object adas = null;
        Object light = null;
        Object instrument = null;
        Object settingDev = null;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (adas == null) adas = getDevice("android.hardware.bydauto.adas.BYDAutoADASDevice", context);
                if (light == null) light = getDevice("android.hardware.bydauto.light.BYDAutoLightDevice", context);
                if (instrument == null) instrument = getDevice("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", context);
                if (settingDev == null) settingDev = getDevice("android.hardware.bydauto.setting.BYDAutoSettingDevice", context);

                Integer bsd = integer(adas, "getBSDState");
                if (bsd != null) fastBsdState = bsd;

                Integer turnFlash = integer(light, "getTurnLightFlashState");
                if (turnFlash != null) fastTurnFlashState = turnFlash;

                Integer turnLight = integer(light, "getTurnLightState");
                if (turnLight != null) fastTurnLightState = turnLight;

                Integer doubleFlash = integer(light, "getDoubleFlashLightState");
                if (doubleFlash != null) fastDoubleFlashState = doubleFlash;

                Integer buzzle = integer(instrument, "getAlarmBuzzleState");
                if (buzzle != null) fastClusterBuzzleState = buzzle;

                Integer dashAlarm = integer(instrument, "getDashboardAlarmState");
                if (dashAlarm != null) fastClusterDashboardAlarm = dashAlarm;

                Integer soundFreq = integer(instrument, "getSoundFreq");
                if (soundFreq != null) fastClusterSoundFreq = soundFreq;

                Integer soundType = integer(instrument, "getSoundType");
                if (soundType != null) fastClusterSoundType = soundType;

                // Directional BSD & DOW hazard indicators
                // 0x41800020: ADAS_DOW_WARN_LEFT
                // 0x41800022: ADAS_DOW_WARN_RIGHT
                Integer dowLeft = getFeature(adas, 0x41800020);
                if (dowLeft != null && dowLeft >= 0) fastDowWarnLeft = dowLeft;

                Integer dowRight = getFeature(adas, 0x41800022);
                if (dowRight != null && dowRight >= 0) fastDowWarnRight = dowRight;

                // 0x49400014: SETTING_LEFT_OUT_MIRROR_SYSTEM_WORKING_STATUS
                // 0x41C00018: SETTING_RIGHT_OUT_MIRROR_SYSTEM_WORKING_STATUS
                Integer mirrorLeft = getFeature(settingDev, 0x49400014);
                if (mirrorLeft != null && mirrorLeft >= 0) fastLeftMirrorStatus = mirrorLeft;

                Integer mirrorRight = getFeature(settingDev, 0x41C00018);
                if (mirrorRight != null && mirrorRight >= 0) fastRightMirrorStatus = mirrorRight;

                // Turn signal directions: 2, 3 = Left; 4, 5 = Right
                fastLeftTurn = (fastTurnFlashState == 2 || fastTurnFlashState == 3 || fastTurnLightState == 2 || fastTurnLightState == 3);
                fastRightTurn = (fastTurnFlashState == 4 || fastTurnFlashState == 5 || fastTurnLightState == 4 || fastTurnLightState == 5);

                // Danger on respective sides (BYD BSD: 2=Left, 3=Right, 4=Both/General hazard >= 2)
                boolean bsdLeft = (fastBsdState == 2 || fastBsdState == 4 || (fastBsdState >= 2 && fastLeftTurn));
                boolean bsdRight = (fastBsdState == 3 || fastBsdState == 4 || (fastBsdState >= 2 && fastRightTurn));

                fastLeftDanger = bsdLeft || (fastDowWarnLeft > 0);
                fastRightDanger = bsdRight || (fastDowWarnRight > 0);

                // Strict Match Gate: Left signal with Left danger, or Right signal with Right danger
                fastBsdAlertActive = (fastLeftTurn && fastLeftDanger) || (fastRightTurn && fastRightDanger);

                JSONObject cur = snapshot;
                if (cur != null) {
                    synchronized (BydTelemetryDaemon.class) {
                        cur.put("bsdState", fastBsdState);
                        cur.put("turnFlashState", fastTurnFlashState);
                        cur.put("turnLightState", fastTurnLightState);
                        cur.put("doubleFlashState", fastDoubleFlashState);
                        cur.put("clusterBuzzleState", fastClusterBuzzleState);
                        cur.put("clusterDashboardAlarm", fastClusterDashboardAlarm);
                        cur.put("clusterSoundFreq", fastClusterSoundFreq);
                        cur.put("clusterSoundType", fastClusterSoundType);
                        cur.put("dowWarnLeft", fastDowWarnLeft);
                        cur.put("dowWarnRight", fastDowWarnRight);
                        cur.put("leftMirrorStatus", fastLeftMirrorStatus);
                        cur.put("rightMirrorStatus", fastRightMirrorStatus);
                        cur.put("isLeftTurn", fastLeftTurn);
                        cur.put("isRightTurn", fastRightTurn);
                        cur.put("isLeftDanger", fastLeftDanger);
                        cur.put("isRightDanger", fastRightDanger);
                        cur.put("bsdAlertActive", fastBsdAlertActive);
                    }
                }
            } catch (Throwable ignored) {}
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void pollLoop(Context context) {
        Object statistic = null;
        Object power = null;
        Object instrument = null;
        Object ac = null;
        Object speedDev = null;
        Object gearbox = null;
        Object tyre = null;
        Object ota = null;
        Object sensor = null;
        long lastLog = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (statistic == null) {
                    statistic = getDevice("android.hardware.bydauto.statistic.BYDAutoStatisticDevice", context);
                    if (statistic != null) registerStatisticListener(statistic);
                }
                if (power == null) power = getDevice("android.hardware.bydauto.power.BYDAutoPowerDevice", context);
                if (instrument == null) instrument = getDevice("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", context);
                if (ac == null) ac = getDevice("android.hardware.bydauto.ac.BYDAutoAcDevice", context);
                if (speedDev == null) speedDev = getDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", context);
                if (gearbox == null) gearbox = getDevice("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", context);
                if (tyre == null) tyre = getDevice("android.hardware.bydauto.tyre.BYDAutoTyreDevice", context);
                if (ota == null) ota = getDevice("android.hardware.bydauto.ota.BYDAutoOtaDevice", context);
                if (sensor == null) sensor = getDevice("android.hardware.bydauto.sensor.BYDAutoSensorDevice", context);

                JSONObject data = new JSONObject();
                data.put("uid", android.os.Process.myUid());
                data.put("source", "BYD SDK + BMS Collector via app_process");
                data.put("updatedAtMs", SystemClock.elapsedRealtime());

                Double polledSoc = number(statistic, "getElecPercentageValue");
                if (polledSoc != null && polledSoc > 100.0 && polledSoc <= 1000.0) polledSoc /= 10.0;
                Double soc = callbackSoc >= 0.0 ? callbackSoc : polledSoc;

                Double statisticRemaining = number(statistic, "getStatisticRemainingBatteryPower");
                Double lastConsumption = number(statistic, "getLastElecConPHMValue");
                Double totalElecCon = number(statistic, "getTotalElecConValue");
                Double instantElecCon = number(statistic, "getInstantElecConValue");
                Double totalMileage = number(statistic, "getTotalMileageValue");
                Double waterTemp = number(statistic, "getWaterTemperature");

                Integer polledRange = integer(statistic, "getElecDrivingRangeValue");
                Integer range = callbackRangeKm >= 0 ? callbackRangeKm : polledRange;

                Double polledRemain = numberAny(power, "getBatteryRemainPowerEV", "getRemainingBatteryPower", "getEVRemainingBatteryPower");
                Double remain = callbackRemainKwh >= 0.0f ? (double) callbackRemainKwh : polledRemain;

                Double consumption = number(instrument, "getLast50KmPowerConsume");
                if (consumption == null || consumption <= 0.0) consumption = lastConsumption;

                // Parse BMS log (/data/local/tmp/byddatacollector.log) for HV Battery Temp, Cell Voltage, and 12V
                double hvTempMax = -100.0;
                double hvTempMin = -100.0;
                double hvTempAvg = -100.0;
                double cellVoltMax = -1.0;
                double cellVoltMin = -1.0;
                double aux12v = -1.0;

                String tailLog = readTail("/data/local/tmp/byddatacollector.log", 4096);
                if (tailLog != null && !tailLog.isEmpty()) {
                    Matcher m = BMS_LOG_PATTERN.matcher(tailLog);
                    while (m.find()) {
                        try {
                            double logSoc = Double.parseDouble(m.group(1));
                            if ((soc == null || soc <= 0.0) && logSoc >= 0.0 && logSoc <= 100.0) {
                                soc = logSoc;
                            }
                            aux12v = Double.parseDouble(m.group(2));
                            hvTempMax = Double.parseDouble(m.group(3));
                            hvTempMin = Double.parseDouble(m.group(4));
                            hvTempAvg = Double.parseDouble(m.group(5));
                            cellVoltMax = Double.parseDouble(m.group(6));
                            cellVoltMin = Double.parseDouble(m.group(7));
                        } catch (Throwable ignored) {}
                    }
                }

                // Fallback 12V from OTA device if not in log
                if (aux12v <= 0.0) {
                    Double ota12v = number(ota, "getBatteryVoltage");
                    if (ota12v != null && ota12v > 5.0 && ota12v < 20.0) aux12v = ota12v;
                }

                // Fallback HV temp from AC sub battery temp or water temp
                if (hvTempAvg <= -50.0) {
                    Double subTemp = number(ac, "getAcSubBatteryTemperature");
                    if (subTemp != null && subTemp > -40.0 && subTemp < 100.0) {
                        hvTempAvg = subTemp;
                        hvTempMax = subTemp;
                        hvTempMin = subTemp;
                    } else if (waterTemp != null && waterTemp > -40.0 && waterTemp < 100.0) {
                        hvTempAvg = waterTemp;
                    }
                }

                // Real Cabin Indoor Sensor Temp + AC Setpoint + Humidity + Outside Temp
                Integer acCabinTemp = integerWithIntArg(ac, "getTemprature", 3);
                Integer acSetpointTemp = integerWithIntArg(ac, "getTemprature", 1);
                Double realCabinSensorTemp = number(sensor, "getTemperatureSensorValue");
                Double cabinHumidity = number(sensor, "getHumiditySensorValue");
                Integer outsideTemp = integer(instrument, "getOutCarTemperature");
                if (outsideTemp == null) outsideTemp = integerWithIntArg(ac, "getTemprature", 4);

                // Priority for cabin temperature:
                // 1. AC Interior Sensor (getTemprature area 3: AC_TEMP_IN_CAR)
                // 2. Physical cabin thermistor (getTemperatureSensorValue)
                // 3. AC Setpoint Temperature (getTemprature area 1: AC_TEMP_LEFT)
                // NEVER fall back to HV battery temperature for cabin temperature!
                Integer cabinTemp = null;
                if (acCabinTemp != null && acCabinTemp >= 10 && acCabinTemp <= 55) {
                    cabinTemp = acCabinTemp;
                } else if (realCabinSensorTemp != null && realCabinSensorTemp >= 10.0 && realCabinSensorTemp <= 55.0) {
                    cabinTemp = (int) Math.round(realCabinSensorTemp);
                } else if (acSetpointTemp != null && acSetpointTemp >= 14 && acSetpointTemp <= 35) {
                    cabinTemp = acSetpointTemp;
                }
                boolean hasValidRealCabinSensor = (cabinTemp != null);

                // Speed & Gear
                Double currentSpeed = number(speedDev, "getCurrentSpeed");
                Integer gearCode = integer(gearbox, "getGearboxAutoModeType");
                if (gearCode == null) gearCode = integer(gearbox, "getCurrentGear");

                // TPMS Tire Pressure (1=FL, 2=FR, 3=RL, 4=RR on BYD SDK)
                Integer tpmsFl = integerWithIntArg(tyre, "getTyrePressureValue", 1);
                if (tpmsFl == null || tpmsFl < 0 || tpmsFl > 600) tpmsFl = integerWithIntArg(tyre, "getTyrePressureValue", 0);
                Integer tpmsFr = integerWithIntArg(tyre, "getTyrePressureValue", 2);
                Integer tpmsRl = integerWithIntArg(tyre, "getTyrePressureValue", 3);
                Integer tpmsRr = integerWithIntArg(tyre, "getTyrePressureValue", 4);

                if (soc != null && soc >= 0 && soc <= 100) {
                    JSONObject socObject = new JSONObject();
                    socObject.put("percent", soc);
                    data.put("soc", socObject);
                }
                if (range != null && range > 0 && range < 1500) {
                    JSONObject rangeObject = new JSONObject();
                    rangeObject.put("elecRangeKm", range);
                    data.put("range", rangeObject);
                }
                if (remain != null && remain > 0 && remain < 200) data.put("remainEnergyKwh", remain);
                if (statisticRemaining != null) data.put("statisticRemainingBatteryPowerRaw", statisticRemaining);
                if (lastConsumption != null) data.put("lastElecConPHMRaw", lastConsumption);
                if (consumption != null && consumption > 0 && consumption < 100) {
                    JSONObject consumptionObject = new JSONObject();
                    consumptionObject.put("kwhPer100km", consumption);
                    data.put("consumption", consumptionObject);
                }
                if (totalElecCon != null && totalElecCon >= 0) data.put("totalElecConKwh", totalElecCon);
                if (instantElecCon != null) data.put("instantElecConKw", instantElecCon);
                if (totalMileage != null && totalMileage >= 0) data.put("totalMileageKm", totalMileage);

                if (hvTempAvg > -50.0) {
                    data.put("hvBattTempAvgC", hvTempAvg);
                    data.put("hvBattTempMaxC", hvTempMax);
                    data.put("hvBattTempMinC", hvTempMin);
                }
                if (cellVoltMax > 0.0) {
                    data.put("cellVoltMaxV", cellVoltMax);
                    data.put("cellVoltMinV", cellVoltMin);
                }
                if (aux12v > 0.0) data.put("aux12v", aux12v);
                if (cabinTemp != null && cabinTemp >= 1.0 && cabinTemp <= 70) data.put("cabinTempC", cabinTemp);
                if (hasValidRealCabinSensor) {
                    data.put("realCabinSensorTempC", realCabinSensorTemp);
                }
                if (acSetpointTemp != null && acSetpointTemp >= 14 && acSetpointTemp <= 40) {
                    data.put("acSetpointTempC", acSetpointTemp);
                }
                if (cabinHumidity != null && cabinHumidity >= 0.0 && cabinHumidity <= 100.0) {
                    data.put("cabinHumidityPercent", cabinHumidity);
                }
                if (outsideTemp != null && outsideTemp >= -40 && outsideTemp <= 70) data.put("outsideTempC", outsideTemp);
                if (currentSpeed != null && currentSpeed >= 0 && currentSpeed <= 300) data.put("speedKmh", currentSpeed.intValue());
                if (gearCode != null) data.put("gearCode", gearCode);

                if (tpmsFl != null || tpmsFr != null || tpmsRl != null || tpmsRr != null) {
                    JSONObject tpmsObj = new JSONObject();
                    if (tpmsFl != null && tpmsFl > 0 && tpmsFl < 600) tpmsObj.put("fl", tpmsFl);
                    if (tpmsFr != null && tpmsFr > 0 && tpmsFr < 600) tpmsObj.put("fr", tpmsFr);
                    if (tpmsRl != null && tpmsRl > 0 && tpmsRl < 600) tpmsObj.put("rl", tpmsRl);
                    if (tpmsRr != null && tpmsRr > 0 && tpmsRr < 600) tpmsObj.put("rr", tpmsRr);
                    data.put("tpms", tpmsObj);
                }

                // Include fast safety states
                data.put("bsdState", fastBsdState);
                data.put("turnFlashState", fastTurnFlashState);
                data.put("turnLightState", fastTurnLightState);
                data.put("clusterBuzzleState", fastClusterBuzzleState);
                data.put("clusterDashboardAlarm", fastClusterDashboardAlarm);
                data.put("clusterSoundFreq", fastClusterSoundFreq);
                data.put("clusterSoundType", fastClusterSoundType);
                data.put("dowWarnLeft", fastDowWarnLeft);
                data.put("dowWarnRight", fastDowWarnRight);
                data.put("leftMirrorStatus", fastLeftMirrorStatus);
                data.put("rightMirrorStatus", fastRightMirrorStatus);
                data.put("isLeftTurn", fastLeftTurn);
                data.put("isRightTurn", fastRightTurn);
                data.put("isLeftDanger", fastLeftDanger);
                data.put("isRightDanger", fastRightDanger);
                data.put("bsdAlertActive", fastBsdAlertActive);

                boolean validSoc = soc != null && soc >= 0.0 && soc <= 100.0;
                data.put("status", validSoc ? "OK" : "WAITING_FOR_SOC");

                synchronized (BydTelemetryDaemon.class) {
                    snapshot = data;
                }

                long now = SystemClock.elapsedRealtime();
                if (now - lastLog > 30000) {
                    log("Telemetry status=" + data.optString("status")
                            + ", soc=" + soc + ", remainKwh=" + remain
                            + ", hvTemp=" + hvTempMax + "/" + hvTempMin
                            + ", cellV=" + cellVoltMax + "/" + cellVoltMin);
                    lastLog = now;
                }
            } catch (Throwable t) {
                snapshot = unavailable(t.getClass().getSimpleName() + ": " + t.getMessage());
                log("Poll failed: " + t);
            }
            try { Thread.sleep(1500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String readTail(String path, int maxBytes) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            if (len <= 0) return null;
            long start = Math.max(0, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static synchronized void registerStatisticListener(Object device) {
        if (registeredStatisticDevice == device) return;
        try {
            Class<?> listenerType = Class.forName(
                    "android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener",
                    true,
                    device.getClass().getClassLoader());
            Method register = device.getClass().getMethod("registerListener", listenerType);
            register.invoke(device, STATISTIC_LISTENER);
            registeredStatisticDevice = device;
        } catch (NoSuchMethodException missingSingleArg) {
            try {
                Class<?> listenerType = Class.forName(
                        "android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener",
                        true,
                        device.getClass().getClassLoader());
                Method register = device.getClass().getMethod(
                        "registerListener", listenerType, int[].class);
                register.invoke(device, STATISTIC_LISTENER, new int[0]);
                registeredStatisticDevice = device;
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static Object getDevice(String className, Context context) {
        try {
            Class<?> cls = Class.forName(className);
            try {
                Method self = cls.getMethod("self");
                if (java.lang.reflect.Modifier.isStatic(self.getModifiers())) {
                    Object value = self.invoke(null);
                    if (value != null) return value;
                }
            } catch (Throwable ignored) {}

            try {
                Method factory = cls.getMethod("getInstance", Context.class);
                Object value = factory.invoke(null, context);
                if (value != null) return value;
            } catch (Throwable ignored) {}

            try {
                Method factory = cls.getMethod("getInstance");
                Object value = factory.invoke(null);
                if (value != null) return value;
            } catch (Throwable ignored) {}
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Double number(Object target, String methodName) {
        if (target == null) return null;
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Number ? ((Number) value).doubleValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Double numberAny(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            Double value = number(target, methodName);
            if (value != null && value > 0.0) return value;
        }
        return null;
    }

    private static Integer integer(Object target, String methodName) {
        Double value = number(target, methodName);
        return value == null ? null : value.intValue();
    }

    private static Integer integerWithIntArg(Object target, String methodName, int arg) {
        if (target == null) return null;
        try {
            Object value = target.getClass().getMethod(methodName, int.class).invoke(target, arg);
            return value instanceof Number ? ((Number) value).intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer getFeature(Object device, int featureId) {
        if (device == null) return null;
        try {
            Method mGet = null;
            Class<?> cur = device.getClass();
            while (cur != null) {
                try {
                    mGet = cur.getDeclaredMethod("get", int.class, int.class);
                    break;
                } catch (Throwable t) {
                    cur = cur.getSuperclass();
                }
            }
            if (mGet == null) return null;
            mGet.setAccessible(true);
            int devType = (Integer) device.getClass().getMethod("getType").invoke(device);
            Object res = mGet.invoke(device, devType, featureId);
            return res instanceof Number ? ((Number) res).intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject unavailable(String reason) {
        JSONObject data = new JSONObject();
        try {
            data.put("status", "UNAVAILABLE");
            data.put("error", reason == null ? "" : reason);
            data.put("uid", android.os.Process.myUid());
            data.put("source", "BYD SDK via app_process");
        } catch (Exception ignored) {}
        return data;
    }

    private static void serveLoop() throws Exception {
        ServerSocket server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(1000);
        log("HTTP telemetry endpoint listening on 127.0.0.1:" + PORT);
        while (true) {
            try {
                final Socket client = server.accept();
                Thread worker = new Thread(() -> handleClient(client), "BydHttpClient");
                worker.setDaemon(true);
                worker.start();
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Throwable t) {
                log("HTTP accept error: " + t);
            }
        }
    }

    private static void handleClient(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(1500);
            BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {}

            String jsonStr;
            synchronized (BydTelemetryDaemon.class) {
                jsonStr = snapshot.toString();
            }
            byte[] body = jsonStr.getBytes("UTF-8");
            OutputStream out = c.getOutputStream();
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Cache-Control: no-store\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n";
            out.write(headers.getBytes("UTF-8"));
            out.write(body);
            out.flush();
        } catch (Throwable t) {
            log("HTTP client error: " + t);
        }
    }

    private static void log(String message) {
        System.out.println(TAG + ": " + message);
        android.util.Log.i(TAG, message);
    }
}
