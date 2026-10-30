package com.sunpath.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;

/** Location, weather and units shared between the app and the widget. */
final class WidgetStore {
    private WidgetStore() { }

    private static final String PREFS = "sunpath_widget";
    private static final long WEATHER_STALE_MS = 30 * 60 * 1000L;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Loc {
        String name, tz, source;
        double lat, lon;

        TimeZone timeZone() {
            if (tz == null || tz.isEmpty()) return TimeZone.getDefault();
            TimeZone t = TimeZone.getTimeZone(tz);
            if ("GMT".equals(t.getID()) && !"GMT".equals(tz)) return TimeZone.getDefault();
            return t;
        }
    }

    static Loc loc(Context c) {
        SharedPreferences p = prefs(c);
        Loc l = new Loc();
        if (p.contains("lat")) {
            l.name = p.getString("name", "");
            l.lat = Double.longBitsToDouble(p.getLong("lat", 0));
            l.lon = Double.longBitsToDouble(p.getLong("lon", 0));
            l.tz = p.getString("tz", "");
            l.source = p.getString("source", "");
        } else {
            l.name = "Chicago";
            l.lat = 41.8781;
            l.lon = -87.6298;
            l.tz = TimeZone.getDefault().getID();
            l.source = "default";
        }
        return l;
    }

    static void saveLoc(Context c, String name, double lat, double lon, String tz, String source) {
        prefs(c).edit()
                .putString("name", name == null ? "" : name)
                .putLong("lat", Double.doubleToRawLongBits(lat))
                .putLong("lon", Double.doubleToRawLongBits(lon))
                .putString("tz", tz == null ? "" : tz)
                .putString("source", source == null ? "" : source)
                .apply();
    }

    static String units(Context c) { return prefs(c).getString("units", "F"); }

    static final class Wx {
        boolean has;
        int code;
        double cover, tempF, lat, lon;
        long ts;
    }

    static Wx wx(Context c) {
        SharedPreferences p = prefs(c);
        Wx w = new Wx();
        w.has = p.contains("wx_ts");
        w.code = p.getInt("wx_code", 0);
        w.cover = Double.longBitsToDouble(p.getLong("wx_cover", Double.doubleToRawLongBits(-1)));
        w.tempF = Double.longBitsToDouble(p.getLong("wx_temp", 0));
        w.lat = Double.longBitsToDouble(p.getLong("wx_lat", 0));
        w.lon = Double.longBitsToDouble(p.getLong("wx_lon", 0));
        w.ts = p.getLong("wx_ts", 0);
        return w;
    }

    static void saveWx(Context c, int code, double cover, double tempF, double lat, double lon) {
        prefs(c).edit()
                .putInt("wx_code", code)
                .putLong("wx_cover", Double.doubleToRawLongBits(cover))
                .putLong("wx_temp", Double.doubleToRawLongBits(tempF))
                .putLong("wx_lat", Double.doubleToRawLongBits(lat))
                .putLong("wx_lon", Double.doubleToRawLongBits(lon))
                .putLong("wx_ts", System.currentTimeMillis())
                .apply();
    }

    /** Called by the app page whenever its location, units or live weather change. */
    static void syncFromApp(Context c, String json) {
        try {
            JSONObject j = new JSONObject(json);
            JSONObject l = j.optJSONObject("loc");
            if (l != null && l.has("lat") && l.has("lon")) {
                saveLoc(c, l.optString("name"), l.getDouble("lat"), l.getDouble("lon"), l.optString("tz"), l.optString("source"));
            }
            String u = j.optString("units", "");
            if ("F".equals(u) || "C".equals(u)) prefs(c).edit().putString("units", u).apply();
            JSONObject w = j.optJSONObject("weather");
            if (w != null && l != null) {
                saveWx(c, w.optInt("code", 0), w.optDouble("cover", -1), w.optDouble("temp", 0), l.optDouble("lat"), l.optDouble("lon"));
            }
        } catch (Exception ignored) {
            // malformed sync from the page: keep what we had
        }
    }

    /** For "My location", refresh coordinates from the phone's last known fix when allowed. */
    static void refreshDeviceLocation(Context c) {
        Loc l = loc(c);
        if (!"gps".equals(l.source) && !"default".equals(l.source)) return;
        boolean fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location x = lm.getLastKnownLocation(provider);
                if (x != null && (best == null || x.getTime() > best.getTime())) best = x;
            }
            if (best != null) {
                double lat = Math.round(best.getLatitude() * 1e4) / 1e4;
                double lon = Math.round(best.getLongitude() * 1e4) / 1e4;
                saveLoc(c, "My location", lat, lon, TimeZone.getDefault().getID(), "gps");
            }
        } catch (RuntimeException ignored) {
            // no fix available in the background; keep the saved coordinates
        }
    }

    static boolean weatherStale(Context c) {
        Wx w = wx(c);
        if (!w.has) return true;
        Loc l = loc(c);
        boolean moved = Math.abs(w.lat - l.lat) > 0.2 || Math.abs(w.lon - l.lon) > 0.2;
        return moved || System.currentTimeMillis() - w.ts > WEATHER_STALE_MS;
    }

    /** Fetches current conditions from Open-Meteo. Call off the main thread. */
    static boolean fetchWeather(Context c) {
        Loc l = loc(c);
        HttpURLConnection con = null;
        try {
            URL url = new URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,cloud_cover&temperature_unit=fahrenheit&timezone=auto",
                    l.lat, l.lon));
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            if (con.getResponseCode() != 200) return false;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
            saveWx(c, cur.getInt("weather_code"), cur.optDouble("cloud_cover", -1), cur.getDouble("temperature_2m"), l.lat, l.lon);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
