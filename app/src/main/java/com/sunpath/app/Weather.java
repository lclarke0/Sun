package com.sunpath.app;

/** Maps Open-Meteo WMO weather codes to what the sky should look like. */
final class Weather {
    private Weather() { }

    static final int NONE = 0, RAIN = 1, SNOW = 2;

    static final class Sky {
        String label;
        float cover;
        int precip = NONE;
        float rate;
        boolean fog, storm;

        Sky(String label, float cover) { this.label = label; this.cover = cover; }
        Sky precip(int type, float rate) { this.precip = type; this.rate = rate; return this; }
    }

    static Sky fromCode(int code, double coverPct) {
        Sky s = base(code);
        if (coverPct >= 0) s.cover = (float) Math.min(1, coverPct / 100.0);
        return s;
    }

    private static Sky base(int c) {
        if (c == 0) return new Sky("Clear", .03f);
        if (c == 1) return new Sky("Mostly clear", .2f);
        if (c == 2) return new Sky("Partly cloudy", .45f);
        if (c == 3) return new Sky("Overcast", .96f);
        if (c == 45 || c == 48) { Sky s = new Sky("Fog", .7f); s.fog = true; return s; }
        if (c >= 51 && c <= 57) return new Sky(c >= 56 ? "Freezing drizzle" : "Drizzle", .95f).precip(RAIN, .25f);
        if (c == 61) return new Sky("Light rain", 1f).precip(RAIN, .38f);
        if (c == 63) return new Sky("Rain", 1f).precip(RAIN, .62f);
        if (c == 65) return new Sky("Heavy rain", 1f).precip(RAIN, 1f);
        if (c == 66 || c == 67) return new Sky("Freezing rain", 1f).precip(RAIN, .6f);
        if (c == 71) return new Sky("Light snow", .95f).precip(SNOW, .35f);
        if (c == 73 || c == 77) return new Sky("Snow", .95f).precip(SNOW, .6f);
        if (c == 75) return new Sky("Heavy snow", 1f).precip(SNOW, 1f);
        if (c == 80) return new Sky("Showers", .8f).precip(RAIN, .4f);
        if (c == 81) return new Sky("Rain showers", .85f).precip(RAIN, .65f);
        if (c == 82) return new Sky("Heavy showers", 1f).precip(RAIN, 1f);
        if (c == 85 || c == 86) return new Sky("Snow showers", .9f).precip(SNOW, .6f);
        if (c >= 95) { Sky s = new Sky("Thunderstorm", 1f).precip(RAIN, 1f); s.storm = true; return s; }
        return new Sky("Cloudy", .7f);
    }
}
