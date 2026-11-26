package com.rxvy.tapedeck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps music playing in the background: media notification, lock-screen controls, headset / Bluetooth buttons. */
public class PlaybackService extends Service implements Player.Listener {
    static boolean running;
    static final String CH = "playback", A_TOGGLE = "tapedeck.toggle", A_NEXT = "tapedeck.next", A_PREV = "tapedeck.prev", A_CLOSE = "tapedeck.close";
    static final int ID = 1;

    Player pl; MediaSession ms; NotificationManager nm; boolean fg;
    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    long artId = Long.MIN_VALUE; Bitmap art; String metaKey = "";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        pl = Player.get(this);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false); ch.setDescription("Music controls"); nm.createNotificationChannel(ch);
        }
        ms = new MediaSession(this, "TapeDeck");
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { pl.play(); }
            @Override public void onPause() { pl.pause(); }
            @Override public void onSkipToNext() { pl.next(true); }
            @Override public void onSkipToPrevious() { pl.prev(); }
            @Override public void onSeekTo(long p) { pl.seek((int) p); }
            @Override public void onStop() { pl.pause(); }
        });
        ms.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        ms.setSessionActivity(openApp());
        ms.setActive(true);
        pl.listen(this);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        // A service started with startForegroundService must call startForeground right away.
        goForeground(build());
        if (A_TOGGLE.equals(a)) { if (!pl.toggle()) pl.play(); }
        else if (A_NEXT.equals(a)) pl.next(true);
        else if (A_PREV.equals(a)) pl.prev();
        else if (A_CLOSE.equals(a)) { pl.pause(); stopSelf(); return START_NOT_STICKY; }
        changed();
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent i) { if (!pl.playing()) stopSelf(); }

    @Override public void onDestroy() {
        running = false;
        pl.unlisten(this);
        try { ms.setActive(false); ms.release(); } catch (Throwable e) { }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        nm.cancel(ID);
        ex.shutdownNow();
        super.onDestroy();
    }

    PendingIntent openApp() {
        Intent o = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, o, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    PendingIntent act(String a, int rq) {
        Intent i = new Intent(this, PlaybackService.class).setAction(a);
        return PendingIntent.getService(this, rq, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    int icon() {
        int r = getResources().getIdentifier("ic_stat_tape", "drawable", getPackageName());
        return r != 0 ? r : android.R.drawable.ic_media_play;
    }

    @SuppressWarnings("deprecation")
    Notification build() {
        Song s = pl.now(); boolean on = pl.playing();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(icon()).setContentTitle(s == null ? "Tape Deck" : s.title).setContentText(s == null ? "Nothing playing" : s.artist)
            .setSubText(s == null ? null : s.album).setContentIntent(openApp()).setDeleteIntent(act(A_CLOSE, 4))
            .setVisibility(Notification.VISIBILITY_PUBLIC).setShowWhen(false).setOngoing(on).setCategory(Notification.CATEGORY_TRANSPORT);
        if (art != null && s != null && artId == s.id) b.setLargeIcon(art);
        b.addAction(android.R.drawable.ic_media_previous, "Previous", act(A_PREV, 1));
        b.addAction(on ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, on ? "Pause" : "Play", act(A_TOGGLE, 2));
        b.addAction(android.R.drawable.ic_media_next, "Next", act(A_NEXT, 3));
        b.setStyle(new Notification.MediaStyle().setMediaSession(ms.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    void goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, 2 /* ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK */); else startForeground(ID, n);
            fg = true;
        } catch (Throwable e) { fg = false; try { nm.notify(ID, n); } catch (Throwable e2) { } }
    }

    @Override public void changed() {
        final Song s = pl.now();
        if (s == null) { stopSelf(); return; }
        boolean on = pl.playing();
        long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        ms.setPlaybackState(new PlaybackState.Builder().setActions(acts)
            .setState(on ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pl.pos(), on ? pl.speed : 0f).build());
        boolean withArt = art != null && artId == s.id;
        String key = s.id + (withArt ? "a" : "") + ":" + pl.dur();
        if (!key.equals(metaKey)) {
            MediaMetadata.Builder m = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist).putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, pl.dur());
            if (withArt) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            ms.setMetadata(m.build());
            metaKey = key;
        }
        if (artId != s.id) {
            artId = s.id; art = null;
            try {
                ex.execute(new Runnable() { public void run() {
                    final Bitmap b = Player.loadArt(PlaybackService.this, s, 512);
                    h.post(new Runnable() { public void run() { if (artId == s.id && running) { art = b; if (b != null) changed(); } } });
                }});
            } catch (Throwable e) { }
        }
        Notification n = build();
        if (on) goForeground(n);
        else {
            if (fg) { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH); else stopForeground(false); fg = false; }
            try { nm.notify(ID, n); } catch (Throwable e) { }
        }
    }
}
