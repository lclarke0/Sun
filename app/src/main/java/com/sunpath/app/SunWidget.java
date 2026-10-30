package com.sunpath.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Home-screen widget: a small sky showing the sun's path and position, a live countdown
 * to sunset (or sunrise at night), and the current weather.
 */
public class SunWidget extends AppWidgetProvider {
    static final String ACTION_TICK = "com.sunpath.app.WIDGET_TICK";
    private static final long REFRESH_MS = 10 * 60 * 1000L;
    private static final long WEATHER_SHOW_MS = 3 * 60 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (ACTION_TICK.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                || Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            refreshAsync(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refreshAsync(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        refreshAsync(context);
    }

    @Override
    public void onDisabled(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(tickIntent(context, 1));
        am.cancel(tickIntent(context, 2));
    }

    private void refreshAsync(Context context) {
        final Context ctx = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                WidgetStore.refreshDeviceLocation(ctx);
                updateAll(ctx);
                if (WidgetStore.weatherStale(ctx) && WidgetStore.fetchWeather(ctx)) updateAll(ctx);
            } catch (Throwable ignored) {
                // never let a refresh crash the widget host
            } finally {
                pending.finish();
            }
        }, "sunpath-widget").start();
    }

    /** Redraws every Sunpath widget and schedules the next refresh. Safe to call from any thread. */
    static synchronized void updateAll(Context context) {
        Context ctx = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
        int[] ids = manager.getAppWidgetIds(new ComponentName(ctx, SunWidget.class));
        if (ids == null || ids.length == 0) return;

        long now = System.currentTimeMillis();
        WidgetStore.Loc loc = WidgetStore.loc(ctx);
        SunMath.State st = SunMath.state(now, loc.lat, loc.lon);
        WidgetStore.Wx wx = WidgetStore.wx(ctx);
        boolean showWx = wx.has && now - wx.ts < WEATHER_SHOW_MS;
        Weather.Sky sky = showWx ? Weather.fromCode(wx.code, wx.cover) : Weather.fromCode(0, -1);

        for (int id : ids) {
            try {
                manager.updateAppWidget(id, build(ctx, manager, id, now, loc, st, wx, showWx, sky));
            } catch (RuntimeException ignored) {
                // a single oversized or failed update shouldn't stop the others
            }
        }
        schedule(ctx, st.to);
    }

    private static RemoteViews build(Context ctx, AppWidgetManager manager, int id, long now,
                                     WidgetStore.Loc loc, SunMath.State st, WidgetStore.Wx wx,
                                     boolean showWx, Weather.Sky sky) {
        Bundle o = manager.getAppWidgetOptions(id);
        int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (wDp <= 0) wDp = 280;
        if (hDp <= 0) hDp = 140;
        hDp = Math.max(hDp, 100);

        float d = Math.min(ctx.getResources().getDisplayMetrics().density, 2.5f);
        while ((long) (wDp * d) * (long) (hDp * d) * 4L > 2_400_000L && d > 1f) d -= .25f;
        float corner = 16 * d;
        if (Build.VERSION.SDK_INT >= 31) {
            float sys = ctx.getResources().getDimension(android.R.dimen.system_app_widget_background_radius);
            corner = sys / ctx.getResources().getDisplayMetrics().density * d;
        }
        Bitmap bmp = SkyRenderer.render(Math.round(wDp * d), Math.round(hDp * d), d, corner, now, loc.lat, loc.lon, sky, st);

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_sun);
        v.setImageViewBitmap(R.id.sky, bmp);

        String sign = st.alt < 0 ? "−" : "";
        v.setTextViewText(R.id.sun_now, String.format(Locale.US, "Sun %s%d° %s",
                sign, Math.abs(Math.round(st.alt)), SunMath.compass(st.az)));

        if (showWx) {
            boolean f = !"C".equals(WidgetStore.units(ctx));
            long t = Math.round(f ? wx.tempF : (wx.tempF - 32) * 5 / 9);
            v.setTextViewText(R.id.weather, t + "° · " + sky.label);
            v.setViewVisibility(R.id.weather, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.weather, View.GONE);
        }

        DateFormat tf = android.text.format.DateFormat.getTimeFormat(ctx);
        tf.setTimeZone(loc.timeZone());
        if (st.to != 0) {
            long base = SystemClock.elapsedRealtime() + (st.to - now);
            v.setChronometer(R.id.timer, base, null, true);
            v.setChronometerCountDown(R.id.timer, true);
            v.setViewVisibility(R.id.timer, View.VISIBLE);
            v.setViewVisibility(R.id.polar, View.GONE);
            v.setTextViewText(R.id.label, st.day ? "DAYLIGHT LEFT" : "UNTIL SUNRISE");
            v.setTextViewText(R.id.next_label, st.day ? "Sunset" : "Sunrise");
            v.setTextViewText(R.id.next_time, tf.format(new Date(st.to)));
            v.setViewVisibility(R.id.next_box, View.VISIBLE);
        } else {
            v.setChronometer(R.id.timer, SystemClock.elapsedRealtime(), null, false);
            v.setViewVisibility(R.id.timer, View.GONE);
            v.setViewVisibility(R.id.polar, View.VISIBLE);
            v.setTextViewText(R.id.label, "TODAY");
            v.setTextViewText(R.id.polar, st.day ? "Midnight sun" : "Polar night");
            v.setViewVisibility(R.id.next_box, View.GONE);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    private static PendingIntent tickIntent(Context ctx, int code) {
        Intent i = new Intent(ctx, SunWidget.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Regular redraw every 10 minutes, plus an on-time redraw at the moment the sun rises or sets. */
    private static void schedule(Context ctx, long nextEvent) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC, now + REFRESH_MS, tickIntent(ctx, 1));
        } catch (RuntimeException ignored) { }
        if (nextEvent > now) {
            PendingIntent at = tickIntent(ctx, 2);
            long when = nextEvent + 1000;
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC, when, at);
                } else {
                    am.setWindow(AlarmManager.RTC, when, 60_000L, at);
                }
            } catch (RuntimeException e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC, when, at);
            }
        }
    }
}
