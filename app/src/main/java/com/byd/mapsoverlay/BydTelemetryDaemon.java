package com.byd.mapsoverlay;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.reflect.Method;

/**
 * Runs through app_process as ADB shell (UID 2000), following the proven
 * OverDrive DiLink 3 telemetry pattern. Publishes read-only BYD telemetry
 * over loopback HTTP for the ordinary overlay process.
 */
public final class BydTelemetryDaemon {
    private static final String TAG = "BydTelemetryDaemon";
    private static final int PORT = 19878;
    private static volatile JSONObject snapshot = unavailable("Starting");

    private BydTelemetryDaemon() {}

    public static void main(String[] args) {
        log("Starting; uid=" + android.os.Process.myUid());
        try {
            Context context = createBydContext();
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

    private static Context createBydContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = null;
        try { thread = at.getMethod("currentActivityThread").invoke(null); } catch (Throwable ignored) {}
        if (thread == null) thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        if (system == null) throw new IllegalStateException("system context is null");
        Context app = system.createPackageContext("com.byd.mapsoverlay",
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
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

                Double soc = number(statistic, "getElecPercentageValue");
                Integer range = integer(statistic, "getElecDrivingRangeValue");
                Double remain = number(power, "getBatteryRemainPowerEV");
                Double consumption = number(instrument, "getLast50KmPowerConsume");
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
                if (consumption != null && consumption > 0 && consumption < 100) {
                    JSONObject consumptionObject = new JSONObject();
                    consumptionObject.put("kwhPer100km", consumption);
                    data.put("consumption", consumptionObject);
                }
                data.put("status", soc != null ? "OK" : "WAITING_FOR_SOC");
                snapshot = data;

                long now = SystemClock.elapsedRealtime();
                if (now - lastLog > 30000) {
                    log("Telemetry status=" + data.optString("status")
                            + ", soc=" + (soc == null ? "null" : soc)
                            + ", range=" + (range == null ? "null" : range)
                            + ", remainKwh=" + (remain == null ? "null" : remain)
                            + ", consumption=" + (consumption == null ? "null" : consumption));
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

    private static Object getDevice(String className, Context context) {
        try {
            Class<?> cls = Class.forName(className);
            Method factory = cls.getMethod("getInstance", Context.class);
            return factory.invoke(null, context);
        } catch (Throwable t) {
            log("getInstance failed for " + className + ": " + t);
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
