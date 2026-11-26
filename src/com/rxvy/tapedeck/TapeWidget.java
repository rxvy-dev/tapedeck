package com.rxvy.tapedeck;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home-screen widget: cover, title, artist, previous / play-pause / next. Works with the app closed. */
public class TapeWidget extends AppWidgetProvider {
    static long artId = Long.MIN_VALUE, loadingId = Long.MIN_VALUE; static Bitmap art; static String lastKey = "";
    static final ExecutorService ex = Executors.newSingleThreadExecutor();
    static final Handler h = new Handler(Looper.getMainLooper());

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { update(c, true); }

    static int id(Context c, String type, String name) { return c.getResources().getIdentifier(name, type, c.getPackageName()); }

    static void setArt(long songId, Bitmap b) { artId = songId; art = b; }

    static PendingIntent svc(Context c, String action, int rq) {
        Intent i = new Intent(c, PlaybackService.class).setAction(action);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, rq, i, fl) : PendingIntent.getService(c, rq, i, fl);
    }

    /** Redraws every Tape Deck widget (cheap no-op if nothing changed or no widget is on the home screen). */
    static void update(final Context ctx, boolean force) {
        final Context c = ctx.getApplicationContext();
        AppWidgetManager m = AppWidgetManager.getInstance(c); if (m == null) return;
        ComponentName cn = new ComponentName(c, TapeWidget.class);
        int[] ids = m.getAppWidgetIds(cn); if (ids == null || ids.length == 0) return;
        Player pl = Player.get(c); final Song s = pl.now(); boolean on = pl.playing();
        boolean haveArt = s != null && artId == s.id && art != null;
        String key = (s == null ? "-" : s.id + s.title) + on + haveArt;
        if (!force && key.equals(lastKey)) return;
        lastKey = key;
        int layout = id(c, "layout", "tape_widget"); if (layout == 0) return;
        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        v.setTextViewText(id(c, "id", "w_title"), s == null ? "Tape Deck" : s.title);
        v.setTextViewText(id(c, "id", "w_artist"), s == null ? "Tap ▶ to play" : s.artist);
        v.setImageViewResource(id(c, "id", "w_play"), on ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        if (haveArt) v.setImageViewBitmap(id(c, "id", "w_art"), art); else v.setImageViewResource(id(c, "id", "w_art"), id(c, "mipmap", "ic_launcher"));
        v.setOnClickPendingIntent(id(c, "id", "w_prev"), svc(c, PlaybackService.A_PREV, 11));
        v.setOnClickPendingIntent(id(c, "id", "w_play"), svc(c, PlaybackService.A_TOGGLE, 12));
        v.setOnClickPendingIntent(id(c, "id", "w_next"), svc(c, PlaybackService.A_NEXT, 13));
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent po = PendingIntent.getActivity(c, 14, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        v.setOnClickPendingIntent(id(c, "id", "w_art"), po);
        v.setOnClickPendingIntent(id(c, "id", "w_text"), po);
        m.updateAppWidget(cn, v);
        // fetch the cover in the background if we don't have it yet
        if (s != null && !haveArt && loadingId != s.id) {
            loadingId = s.id;
            try {
                ex.execute(new Runnable() { public void run() {
                    final Bitmap b = Player.loadArt(c, s, 256);
                    h.post(new Runnable() { public void run() { if (b != null) { setArt(s.id, b); update(c, false); } } });
                }});
            } catch (Throwable e) { }
        }
    }
}
