package com.sunpath.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/** Draws the widget's sky: sun path, sun position, clouds and precipitation. */
final class SkyRenderer {
    private SkyRenderer() { }

    /** Height of the ground band at the bottom, in dp; the widget's countdown text sits on it. */
    static final float GROUND_DP = 74f;

    private static final double[] SKY_ALT = {-90, -18, -10, -4, 0, 5, 15, 90};
    private static final int[] SKY_TOP = {0x05070f, 0x070b1a, 0x0e1636, 0x1f2b5e, 0x3b5c9c, 0x5a8fd0, 0x3c84d6, 0x2266c4};
    private static final int[] SKY_HOR = {0x080c1c, 0x0e1532, 0x2b2752, 0xc4645a, 0xf39b5d, 0xf6c78a, 0xacd2f1, 0x9ec9ee};
    private static final float[] BLACK = {0, 0, 0};

    private static float clamp01(double v) { return (float) Math.max(0, Math.min(1, v)); }
    private static float[] rgb(int c) { return new float[]{(c >> 16) & 255, (c >> 8) & 255, c & 255}; }
    private static float[] mix(float[] a, float[] b, double t) {
        return new float[]{(float) (a[0] + (b[0] - a[0]) * t), (float) (a[1] + (b[1] - a[1]) * t), (float) (a[2] + (b[2] - a[2]) * t)};
    }
    private static int b(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
    private static int col(float[] c, double a) { return Color.argb(Math.round(clamp01(a) * 255), b(c[0]), b(c[1]), b(c[2])); }
    private static int argb(double a, int r, int g, int bl) { return Color.argb(Math.round(clamp01(a) * 255), r, g, bl); }

    private static float[][] skyColors(double alt) {
        int i = 0;
        while (i < SKY_ALT.length - 2 && alt > SKY_ALT[i + 1]) i++;
        double f = clamp01((alt - SKY_ALT[i]) / (SKY_ALT[i + 1] - SKY_ALT[i]));
        return new float[][]{mix(rgb(SKY_TOP[i]), rgb(SKY_TOP[i + 1]), f), mix(rgb(SKY_HOR[i]), rgb(SKY_HOR[i + 1]), f)};
    }

    static Bitmap render(int w, int h, float d, float corner, long now, double lat, double lon,
                         Weather.Sky wx, SunMath.State st) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, w, h), corner, corner, Path.Direction.CW);
        c.clipPath(clip);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final double alt = st.alt;
        final float daylight = clamp01((alt + 8) / 14);
        final float cover = wx.cover;
        final float wet = wx.precip != Weather.NONE ? .25f + wx.rate * .25f : 0f;

        // Sky gradient, greyed by cloud and darkened by rain
        float[][] sc = skyColors(alt);
        float[] top = sc[0], hor = sc[1];
        float[] grey = mix(rgb(0x1c2029), rgb(0x8b939f), daylight);
        double g = clamp01(cover * .78 + wet * .3);
        top = mix(top, mix(grey, new float[]{40, 44, 54}, wet), g);
        hor = mix(hor, grey, g * .9);
        if (wet > 0) { top = mix(top, BLACK, wet * .35); hor = mix(hor, BLACK, wet * .25); }

        final float horizonY = h - GROUND_DP * d;
        p.setShader(new LinearGradient(0, 0, 0, horizonY, col(top, 1), col(hor, 1), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, horizonY + 2, p);
        p.setShader(null);

        // Today's path: 24 h centred on now, sampled every 10 minutes
        final int n = 145;
        final double[] pa = new double[n], pz = new double[n];
        double maxAlt = 20, minAlt = -10;
        for (int i = 0; i < n; i++) {
            double[] q = SunMath.position(now + (i - 72) * 600000L, lat, lon);
            pa[i] = q[0];
            pz[i] = q[1];
            maxAlt = Math.max(maxAlt, q[0]);
            minAlt = Math.min(minAlt, q[0]);
        }
        final double centerAz = lat >= 0 ? 180 : 0;
        final double up = (horizonY - 34 * d) / maxAlt;
        final double down = (9 * d) / -minAlt;
        final float sx = x(st.az, centerAz, w), sy = y(alt, horizonY, up, down);

        // Stars
        double starA = clamp01((-alt - 4) / 10) * (1 - cover * .95);
        if (starA > .02) {
            Random rs = new Random(11);
            for (int i = 0; i < 80; i++) {
                float px = rs.nextFloat() * w, py = rs.nextFloat() * horizonY * .92f, r = (.35f + rs.nextFloat()) * d * .8f;
                p.setColor(argb(starA * (.45 + .55 * rs.nextFloat()), 255, 255, 255));
                c.drawCircle(px, py, r, p);
            }
        }

        // Glow around the sun
        if (alt > -6) {
            double glowA = clamp01((alt + 6) / 8) * (1 - cover * .8);
            if (glowA > .01) {
                p.setShader(new RadialGradient(sx, sy, Math.max(w, h) * .55f,
                        new int[]{argb(.55 * glowA, 255, 222, 160), argb(.2 * glowA, 255, 190, 120), argb(0, 255, 190, 120)},
                        new float[]{0, .25f, 1}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, horizonY, p);
                p.setShader(null);
            }
        }

        // Sun disc
        final float sunR = 8 * d;
        if (alt >= -1) {
            double sunA = 1 - cover * .65;
            p.setColor(argb(sunA, 255, 236, 190));
            p.setShadowLayer(16 * d * (float) sunA + 1, 0, 0, argb(.9, 255, 200, 120));
            c.drawCircle(sx, sy, sunR, p);
            p.clearShadowLayer();
        }

        // Clouds (layout changes every half hour so they appear to drift)
        Random rc = new Random(now / 1800000L);
        int nc = Math.round(cover * 9);
        float[] cBase = mix(mix(rgb(0x3d4456), rgb(0xf4f6fa), daylight), hor, .18);
        float[] cCol = wet > 0 ? mix(cBase, new float[]{70, 74, 84}, .45 * daylight) : cBase;
        p.setColor(col(cCol, wet > 0 ? .8 : .7));
        for (int k = 0; k < nc; k++) {
            float cx = (float) ((-.1 + 1.2 * rc.nextDouble()) * w);
            float cy = (float) ((.08 + .6 * rc.nextDouble()) * horizonY);
            float s = (float) ((.6 + .55 * rc.nextDouble()) * w / 360.0);
            Path cp = new Path();
            for (int i = 0; i < 6; i++) {
                float dx = (i - 2.5f) * 22 * s + (rc.nextFloat() * 12 - 6) * s;
                float dy = (rc.nextFloat() * 16 - 10 - ((i > 1 && i < 4) ? 10 : 0)) * s;
                float rr = (16 + rc.nextFloat() * 12) * s;
                cp.addCircle(cx + dx, cy + dy, rr, Path.Direction.CW);
            }
            c.drawPath(cp, p);
        }
        if (cover > .85) {
            p.setShader(new LinearGradient(0, 0, 0, horizonY * .7f, col(cCol, .7 * (cover - .6)), col(cCol, 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, horizonY, p);
            p.setShader(null);
        }

        // Sun path above the horizon, drawn over clouds so it is always visible
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeCap(Paint.Cap.ROUND);
        lp.setStrokeWidth(1.7f * d);
        lp.setPathEffect(new DashPathEffect(new float[]{.1f * d, 5.5f * d}, 0));
        lp.setColor(argb(.7, 255, 255, 255));
        c.drawPath(pathSegments(pa, pz, true, centerAz, w, horizonY, up, down), lp);

        // Marker ring when clouds hide the sun
        if (alt >= -1 && cover > .35) {
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * d);
            ring.setColor(argb(.95, 255, 226, 160));
            c.drawCircle(sx, sy, sunR + 5 * d, ring);
            p.setColor(argb(.35, 255, 236, 190));
            c.drawCircle(sx, sy, sunR + d, p);
        }

        // Ground with gentle hills
        float[] gTop = mix(mix(rgb(0x0a0e18), rgb(0x26332c), daylight), hor, .10);
        float[] gBot = mix(gTop, BLACK, .6);
        Path gp = new Path();
        gp.moveTo(0, horizonY);
        float step = 4 * d;
        for (float px = 0; px <= w + step; px += step) {
            double t = px / (double) w;
            gp.lineTo(px, (float) (horizonY - 4 * d - 5 * d * Math.sin(t * 9 + 1.3) * Math.sin(t * 3.1)));
        }
        gp.lineTo(w, h);
        gp.lineTo(0, h);
        gp.close();
        p.setShader(new LinearGradient(0, horizonY - 10 * d, 0, h, col(gTop, 1), col(gBot, 1), Shader.TileMode.CLAMP));
        c.drawPath(gp, p);
        p.setShader(null);
        Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
        hl.setStrokeWidth(Math.max(1, d * .8f));
        hl.setColor(argb(.28, 255, 255, 255));
        c.drawLine(0, horizonY, w, horizonY, hl);

        // Path below the horizon (squeezed into a thin band) and the hidden sun at night
        lp.setColor(argb(.25, 255, 255, 255));
        c.drawPath(pathSegments(pa, pz, false, centerAz, w, horizonY, up, down), lp);
        if (alt < -1) {
            Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);
            ghost.setColor(argb(.2, 255, 214, 150));
            c.drawCircle(sx, sy, 6.5f * d, ghost);
            ghost.setStyle(Paint.Style.STROKE);
            ghost.setStrokeWidth(1.8f * d);
            ghost.setColor(argb(.85, 255, 214, 150));
            c.drawCircle(sx, sy, 6.5f * d, ghost);
        }

        // Sunrise and sunset marks on the horizon
        p.setColor(argb(.95, 255, 226, 170));
        for (long t : new long[]{st.nearRise, st.nearSet}) {
            if (t == 0) continue;
            float mx = x(SunMath.position(t, lat, lon)[1], centerAz, w);
            mx = Math.max(4 * d, Math.min(w - 4 * d, mx));
            c.drawRect(mx - .8f * d, horizonY - 7 * d, mx + .8f * d, horizonY, p);
        }

        // Fog
        if (wx.fog) {
            float[] fc = mix(rgb(0x2a2f3a), rgb(0xe3e6eb), daylight);
            for (int i = 0; i < 4; i++) {
                float fy = horizonY - 40 * d + i * 16 * d;
                p.setShader(new LinearGradient(0, fy - 24 * d, 0, fy + 24 * d,
                        new int[]{col(fc, 0), col(fc, .38), col(fc, 0)}, null, Shader.TileMode.CLAMP));
                c.drawRect(0, fy - 24 * d, w, fy + 24 * d, p);
                p.setShader(null);
            }
        }

        // Rain, snow, lightning
        double area = (w / (d * 300.0)) * (h / (d * 150.0));
        if (wx.precip == Weather.RAIN) {
            Random rr = new Random(now / 600000L);
            int count = (int) ((40 + wx.rate * 160) * area);
            Paint rain = new Paint(Paint.ANTI_ALIAS_FLAG);
            rain.setStrokeWidth(1.1f * d);
            rain.setColor(daylight > .4 ? argb(.55, 220, 230, 245) : argb(.45, 170, 185, 215));
            for (int i = 0; i < count; i++) {
                float rx = rr.nextFloat() * w * 1.1f, ry = rr.nextFloat() * h, len = (8 + rr.nextFloat() * 7) * d;
                c.drawLine(rx, ry, rx - len * .18f, ry + len, rain);
            }
        } else if (wx.precip == Weather.SNOW) {
            Random rr = new Random(now / 600000L);
            int count = (int) ((30 + wx.rate * 120) * area);
            p.setColor(argb(.85, 255, 255, 255));
            for (int i = 0; i < count; i++) {
                c.drawCircle(rr.nextFloat() * w, rr.nextFloat() * h, (1 + rr.nextFloat() * 1.4f) * d, p);
            }
        }
        if (wx.storm) {
            Random rb = new Random(now / 600000L + 3);
            float bx = (.25f + .5f * rb.nextFloat()) * w, by = horizonY * .12f;
            Path bolt = new Path();
            bolt.moveTo(bx, by);
            while (by < horizonY * .78f) {
                bx += (rb.nextFloat() - .5f) * 22 * d;
                by += (8 + rb.nextFloat() * 10) * d;
                bolt.lineTo(bx, by);
            }
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setStyle(Paint.Style.STROKE);
            bp.setStrokeJoin(Paint.Join.ROUND);
            bp.setStrokeWidth(2.2f * d);
            bp.setColor(argb(.95, 255, 248, 220));
            bp.setShadowLayer(8 * d, 0, 0, argb(.9, 200, 210, 255));
            c.drawPath(bolt, bp);
        }
        return bmp;
    }

    private static float x(double az, double centerAz, int w) {
        return (float) ((((az - centerAz + 540) % 360) / 360.0) * w);
    }

    private static float y(double alt, float horizonY, double up, double down) {
        return (float) (alt >= 0 ? horizonY - alt * up : horizonY - alt * down);
    }

    private static Path pathSegments(double[] pa, double[] pz, boolean above, double centerAz, int w,
                                     float horizonY, double up, double down) {
        Path path = new Path();
        boolean started = false;
        float lastX = 0;
        for (int i = 0; i < pa.length; i++) {
            boolean isAbove = pa[i] >= 0;
            float px = x(pz[i], centerAz, w), py = y(pa[i], horizonY, up, down);
            if (isAbove != above) { started = false; continue; }
            if (started && Math.abs(px - lastX) > w / 2f) started = false;
            if (!started) { path.moveTo(px, py); started = true; } else path.lineTo(px, py);
            lastX = px;
        }
        return path;
    }
}
