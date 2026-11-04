package com.byd.mapsoverlay;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.reflect.Method;

/**
 * Runs through app_process as ADB shell (UID 2000) for native DiLink 3 telemetry.
 * Publishes read-only BYD telemetry over loopback HTTP for the overlay process.
 */
public final class BydTelemetryDaemon {
    private static final String TAG = "BydTelemetryDaemon";
    private static final int PORT = 19890;
    private static volatile JSONObject snapshot = unavailable("Starting");
    private static volatile double callbackSoc = -1.0;
    private static volatile int callbackRangeKm = -1;
    private static volatile float callbackRemainKwh = -1.0f;
    private static volatile Object registeredStatisticDevice;

    // A concrete subclass is required: BYD does not reliably dispatch SoC
    // events through a generic java.lang.reflect.Proxy.
    private static final AbsBYDAutoStatisticListener STATISTIC_LISTENER =
            new AbsBYDAutoStatisticListener() {
        @Override public void onElecPercentageChanged(double percentage) {
            if (percentage >= 0.0 && percentage <= 100.0) {
                callbackSoc = percentage;
                log("BYD callback onElecPercentageChanged=" + percentage);
            } else {
                log("Ignoring out-of-range SoC callback=" + percentage);
            }
        }
        @Override public void onElecDrivingRangeChanged(int rangeKm) {
            if (rangeKm >= 0 && rangeKm <= 1500) {
                callbackRangeKm = rangeKm;
                log("BYD callback onElecDrivingRangeChanged=" + rangeKm);
            }
        }
        @Override public void onEVRemainingBatteryPowerChanged(float remainingKwh) {
            acceptRemainingEnergy("onEVRemainingBatteryPowerChanged", remainingKwh);
        }
        public void onRemainingBatteryPowerChanged(float remainingKwh) {
            acceptRemainingEnergy("onRemainingBatteryPowerChanged(float)", remainingKwh);
        }
        public void onRemainingBatteryPowerChanged(double remainingKwh) {
            acceptRemainingEnergy("onRemainingBatteryPowerChanged(double)", (float) remainingKwh);
        }
        private void acceptRemainingEnergy(String source, float remainingKwh) {
            if (remainingKwh >= 0.0f && remainingKwh <= 200.0f) {
                callbackRemainKwh = remainingKwh;
                log("BYD callback " + source + "=" + remainingKwh);
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
            serveLoop();
        } catch (Throwable t) {
            log("FATAL: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static Context createBydContext(String pkgName) throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = null;

        // app_process does not always have an ActivityThread attached to the
        // current thread. Uses a bounded systemMain bootstrap to
        // avoid hanging the telemetry daemon during ActivityThread creation.
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

        // Last-resort fallback for vendor builds where systemMain cannot be
        // invoked from app_process. Keep the fallback bounded and diagnostic.
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
            log("createPackageContext for " + pkgName + " failed, falling back to com.byd.mapsoverlay.beta");
            try {
                app = system.createPackageContext(
                        "com.byd.mapsoverlay.beta",
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable t2) {
                log("fallback to com.byd.mapsoverlay.beta failed, trying com.byd.mapsoverlay");
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

    private static void pollLoop(Context context) {
        Object statistic = null;
        Object power = null;
        Object instrument = null;
        long lastLog = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (statistic == null) {
                    statistic = getDevice("android.hardware.bydauto.statistic.BYDAutoStatisticDevice", context);
                    log("Statistic device " + (statistic == null ? "unavailable" : "initialized"));
                    if (statistic != null) registerStatisticListener(statistic);
                }
                if (power == null) {
                    power = getDevice("android.hardware.bydauto.power.BYDAutoPowerDevice", context);
                    log("Power device " + (power == null ? "unavailable" : "initialized"));
                }
                if (instrument == null) {
                    instrument = getDevice("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", context);
                    log("Instrument device " + (instrument == null ? "unavailable" : "initialized"));
                }

                JSONObject data = new JSONObject();
                data.put("uid", android.os.Process.myUid());
                data.put("source", "BYD SDK via app_process");
                data.put("updatedAtMs", SystemClock.elapsedRealtime());

                Double polledSoc = number(statistic, "getElecPercentageValue");
                if (polledSoc != null && polledSoc > 100.0 && polledSoc <= 1000.0) polledSoc /= 10.0;
                Double soc = callbackSoc >= 0.0 ? callbackSoc : polledSoc;
                Double statisticRemaining = number(statistic, "getStatisticRemainingBatteryPower");
                Double lastConsumption = number(statistic, "getLastElecConPHMValue");
                Integer polledRange = integer(statistic, "getElecDrivingRangeValue");
                Integer range = callbackRangeKm >= 0 ? callbackRangeKm : polledRange;
                // Support getter names observed across BYD SDK builds.
                Double polledRemain = numberAny(power, "getBatteryRemainPowerEV", "getRemainingBatteryPower", "getEVRemainingBatteryPower");
                Double remain = callbackRemainKwh >= 0.0f ? (double) callbackRemainKwh : polledRemain;
                Double consumption = number(instrument, "getLast50KmPowerConsume");
                if (consumption == null || consumption <= 0.0) consumption = lastConsumption;
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
                boolean validSoc = soc != null && soc >= 0.0 && soc <= 100.0;
                data.put("status", validSoc ? "OK" : "WAITING_FOR_SOC");
                snapshot = data;

                long now = SystemClock.elapsedRealtime();
                if (now - lastLog > 30000) {
                    log("Telemetry status=" + data.optString("status")
                            + ", soc=" + (soc == null ? "null" : soc)
                            + ", range=" + (range == null ? "null" : range)
                            + ", remainKwh=" + (remain == null ? "null" : remain)
                            + ", consumption=" + (consumption == null ? "null" : consumption)
                            + ", statisticRemainingRaw=" + (statisticRemaining == null ? "null" : statisticRemaining)
                            + ", lastElecConPHMRaw=" + (lastConsumption == null ? "null" : lastConsumption));
                    lastLog = now;
                }
            } catch (Throwable t) {
                snapshot = unavailable(t.getClass().getSimpleName() + ": " + t.getMessage());
                log("Poll failed: " + t);
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
            log("Registered concrete BYD AbsBYDAutoStatisticListener successfully");
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
                log("Registered concrete BYD listener using subscribe-all overload");
            } catch (Throwable t) {
                log("BYD listener registration failed: " + t);
            }
        } catch (Throwable t) {
            log("BYD listener registration failed: " + t);
        }
    }

    private static Object getDevice(String className, Context context) {
        try {
            Class<?> cls = Class.forName(className);
            // DiLink 3 wrapper supports both self() and getInstance(Context). Try both
            // without allowing one failed factory to suppress the other.
            try {
                Method self = cls.getMethod("self");
                if (java.lang.reflect.Modifier.isStatic(self.getModifiers())) {
                    Object value = self.invoke(null);
                    if (value != null) {
                        log("Using " + className + ".self()");
                        return value;
                    }
                }
            } catch (Throwable t) {
                log(className + ".self() unavailable: " + t.getClass().getSimpleName());
            }

            try {
                Method factory = cls.getMethod("getInstance", Context.class);
                Object value = factory.invoke(null, context);
                if (value != null) {
                    log("Using " + className + ".getInstance(Context)");
                    return value;
                }
            } catch (Throwable t) {
                log(className + ".getInstance(Context) unavailable: " + t);
            }

            try {
                Method factory = cls.getMethod("getInstance");
                Object value = factory.invoke(null);
                if (value != null) {
                    log("Using " + className + ".getInstance()");
                    return value;
                }
            } catch (Throwable t) {
                log(className + ".getInstance() unavailable: " + t);
            }
            return null;
        } catch (Throwable t) {
            log("BYD device factory failed for " + className + ": " + t);
            return null;
        }
    }

    private static Double number(Object target, String methodName) {
        if (target == null) return null;
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Number ? ((Number) value).doubleValue() : null;
        } catch (Throwable t) {
            log(methodName + " failed: " + t);
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
        ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(1000);
        log("HTTP telemetry endpoint listening on 127.0.0.1:" + PORT);
        while (true) {
            try (Socket client = server.accept()) {
                client.setSoTimeout(1500);
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                byte[] body = snapshot.toString().getBytes("UTF-8");
                OutputStream out = client.getOutputStream();
                String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                        + "Cache-Control: no-store\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n";
                out.write(headers.getBytes("UTF-8"));
                out.write(body);
                out.flush();
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Throwable t) {
                log("HTTP client error: " + t);
            }
        }
    }

    private static void log(String message) {
        System.out.println(TAG + ": " + message);
        android.util.Log.i(TAG, message);
    }
}
