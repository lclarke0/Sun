package com.sunpath.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

/** Lets the app page hand its location, units and live weather to the home-screen widget. */
public final class NativeBridge {
    private final Context ctx;

    NativeBridge(Context context) {
        this.ctx = context.getApplicationContext();
    }

    @JavascriptInterface
    public void sync(String json) {
        WidgetStore.syncFromApp(ctx, json);
        SunWidget.updateAll(ctx);
    }
}
