package com.sunpath.app;

/**
 * Sun position and sunrise/sunset times (same formulas as the in-app page,
 * after Astronomical Algorithms / SunCalc). Plain Java, no Android dependencies.
 */
final class SunMath {
    private SunMath() { }

    private static final double RAD = Math.PI / 180.0;
    private static final double DAY_MS = 86400000.0;
    private static final double J1970 = 2440588;
    private static final double J2000 = 2451545;
    private static final double E = RAD * 23.4397;
    private static final double J0 = 0.0009;

    private static double toDays(long ms) { return ms / DAY_MS - 0.5 + J1970 - J2000; }
    private static long fromJulian(double j) { return Math.round((j + 0.5 - J1970) * DAY_MS); }
    private static double meanAnomaly(double d) { return RAD * (357.5291 + 0.98560028 * d); }
    private static double eclipticLongitude(double m) {
        double c = RAD * (1.9148 * Math.sin(m) + 0.02 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m));
        return m + c + RAD * 102.9372 + Math.PI;
    }
    private static double declination(double l) { return Math.asin(Math.sin(E) * Math.sin(l)); }
    private static double rightAscension(double l) { return Math.atan2(Math.sin(l) * Math.cos(E), Math.cos(l)); }

    /** Returns {altitude in degrees (with refraction), azimuth in degrees clockwise from north}. */
    static double[] position(long ms, double lat, double lng) {
        double lw = RAD * -lng, phi = RAD * lat, d = toDays(ms);
        double m = meanAnomaly(d), l = eclipticLongitude(m);
        double dec = declination(l), ra = rightAscension(l);
        double h = RAD * (280.16 + 360.9856235 * d) - lw - ra;
        double alt = Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(h));
        double azS = Math.atan2(Math.sin(h), Math.cos(h) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi));
        double az = (azS / RAD + 180) % 360;
        if (az < 0) az += 360;
        double hr = Math.max(alt, 0);
        double refr = 0.0002967 / Math.tan(hr + 0.00312536 / (hr + 0.08901179));
        return new double[]{(alt + refr) / RAD, az};
    }

    /** Returns {sunrise, sunset, solar noon} in epoch ms for the solar day nearest ms; 0 when the sun doesn't cross the horizon. */
    static long[] riseSet(long ms, double lat, double lng) {
        double lw = RAD * -lng, phi = RAD * lat, d = toDays(ms);
        double n = Math.round(d - J0 - lw / (2 * Math.PI));
        double ds = J0 + lw / (2 * Math.PI) + n;
        double m = meanAnomaly(ds), l = eclipticLongitude(m), dec = declination(l);
        double jNoon = J2000 + ds + 0.0053 * Math.sin(m) - 0.0069 * Math.sin(2 * l);
        double cw = (Math.sin(-0.833 * RAD) - Math.sin(phi) * Math.sin(dec)) / (Math.cos(phi) * Math.cos(dec));
        if (cw < -1 || cw > 1 || Double.isNaN(cw)) return new long[]{0, 0, fromJulian(jNoon)};
        double w = Math.acos(cw);
        double a = J0 + (w + lw) / (2 * Math.PI) + n;
        double jSet = J2000 + a + 0.0053 * Math.sin(m) - 0.0069 * Math.sin(2 * l);
        double jRise = jNoon - (jSet - jNoon);
        return new long[]{fromJulian(jRise), fromJulian(jSet), fromJulian(jNoon)};
    }

    static final class State {
        boolean day;
        long from, to;          // current daylight/night span; 0 = none (polar day/night)
        long nearRise, nearSet; // closest sunrise/sunset to now, for horizon marks
        double alt, az;
    }

    static State state(long now, double lat, double lng) {
        State s = new State();
        double[] p = position(now, lat, lng);
        s.alt = p[0];
        s.az = p[1];
        long prevRise = 0, prevSet = 0, nextRise = 0, nextSet = 0;
        for (int k = -2; k <= 2; k++) {
            long[] t = riseSet(now + k * 86400000L, lat, lng);
            if (t[0] != 0) {
                if (t[0] <= now) { if (t[0] > prevRise) prevRise = t[0]; }
                else if (nextRise == 0 || t[0] < nextRise) nextRise = t[0];
                if (s.nearRise == 0 || Math.abs(t[0] - now) < Math.abs(s.nearRise - now)) s.nearRise = t[0];
            }
            if (t[1] != 0) {
                if (t[1] <= now) { if (t[1] > prevSet) prevSet = t[1]; }
                else if (nextSet == 0 || t[1] < nextSet) nextSet = t[1];
                if (s.nearSet == 0 || Math.abs(t[1] - now) < Math.abs(s.nearSet - now)) s.nearSet = t[1];
            }
        }
        if (prevRise != 0 || prevSet != 0) s.day = prevRise > prevSet;
        else s.day = s.alt > -0.833;
        if (s.day) { s.from = prevRise; s.to = nextSet; }
        else { s.from = prevSet; s.to = nextRise; }
        return s;
    }

    private static final String[] DIRS = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    static String compass(double az) { return DIRS[(int) Math.round(az / 22.5) % 16]; }
}
