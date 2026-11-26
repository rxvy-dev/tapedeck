package com.rxvy.tapedeck;

import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Size;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Owns the MediaPlayer and the play queue. Lives as long as the app process, so music keeps going
 * with the screen off or the app closed (PlaybackService keeps the process alive and shows the controls).
 */
public final class Player {
    public interface Listener { void changed(); }

    private static Player inst;
    public static Player get(Context c) { if (inst == null) inst = new Player(c.getApplicationContext()); return inst; }

    final Context ctx;
    final Handler h = new Handler(Looper.getMainLooper());
    final AudioManager am;
    final SharedPreferences prefs, counts, stats;
    final ArrayList<Listener> ls = new ArrayList<>();

    public ArrayList<Song> queue = new ArrayList<>();
    public int cur = -1, repeat, eqPreset = -1;          // repeat: 0 off, 1 all, 2 one
    public boolean shuffle;
    public float vol = 1f, speed = 1f;
    public long sleepAt;                                  // 0 = no sleep timer
    public boolean sleepEndOfSong;

    final ArrayList<Integer> order = new ArrayList<>();   // shuffle order: indices into queue (no repeats until everything played)
    int opos = -1, errors;
    MediaPlayer mp;
    boolean prepared, focus, resumeOnGain, noisyOn;
    float duck = 1f, fade = 1f;
    final int session;
    Equalizer eq;
    AudioFocusRequest afr;

    Player(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        prefs = c.getSharedPreferences("MainActivity", 0);   // same file the activity always used
        counts = c.getSharedPreferences("playcounts", 0);
        stats = c.getSharedPreferences("stats", 0);
        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", 0);
        vol = prefs.getFloat("vol", 1f);
        speed = prefs.getFloat("speed", 1f);
        eqPreset = prefs.getInt("eq", -1);
        int s = 0; try { s = am.generateAudioSessionId(); } catch (Throwable e) { s = 0; }
        session = s;
    }

    public void listen(Listener l) { if (!ls.contains(l)) ls.add(l); }
    public void unlisten(Listener l) { ls.remove(l); }
    void fire() {
        syncListen();
        for (Listener l : new ArrayList<>(ls)) try { l.changed(); } catch (Throwable e) { }
        try { TapeWidget.update(ctx, false); } catch (Throwable e) { }
    }

    // ---------- listening stats (for Wrapped): time per song, per hour of day, per day ----------
    long listenAt; Song listenSong;

    /** Credits the time since the last call to the song that was playing, then starts timing the current one. */
    void syncListen() {
        long t = SystemClock.elapsedRealtime();
        if (listenAt > 0 && listenSong != null) {
            long dt = t - listenAt;
            if (dt > 0 && dt < 6 * 3600000L) {
                Calendar c = Calendar.getInstance();
                String day = String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
                String ks = "ms_" + listenSong.id, kh = "h_" + c.get(Calendar.HOUR_OF_DAY), kd = "d_" + day;
                SharedPreferences.Editor e = stats.edit();
                e.putLong(ks, stats.getLong(ks, 0) + dt).putLong(kh, stats.getLong(kh, 0) + dt).putLong(kd, stats.getLong(kd, 0) + dt)
                 .putLong("total", stats.getLong("total", 0) + dt);
                if (!stats.contains("since")) e.putLong("since", System.currentTimeMillis());
                e.apply();
            }
        }
        boolean on = playing();
        listenAt = on ? t : 0; listenSong = on ? now() : null;
    }

    // ---------- state ----------
    public Song now() { return cur >= 0 && cur < queue.size() ? queue.get(cur) : null; }
    public boolean loaded() { return mp != null && prepared; }
    public boolean playing() { try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; } }
    public int pos() { try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; } }
    public int dur() {
        try { if (mp != null && prepared) return mp.getDuration(); } catch (Exception e) { }
        Song s = now(); return s == null ? 0 : s.dur;
    }
    public int plays(Song s) { return counts.getInt(Long.toString(s.id), 0); }

    static Uri uri(Song s) { return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id); }

    // ---------- queue ----------
    public void playList(List<Song> l, int i) {
        if (l.isEmpty()) return;
        queue = new ArrayList<>(l); cur = Math.max(0, Math.min(l.size() - 1, i)); errors = 0;
        buildOrder(); start(true);
    }

    /** Shuffles everything (a real shuffle: every song once before any repeats). */
    public void shuffleAll(List<Song> l) {
        if (l.isEmpty()) return;
        shuffle = true; queue = new ArrayList<>(l); cur = (int) (Math.random() * l.size()); errors = 0;
        buildOrder(); save(); start(true);
    }

    void buildOrder() {
        order.clear();
        for (int i = 0; i < queue.size(); i++) if (i != cur) order.add(i);
        Collections.shuffle(order);
        if (cur >= 0 && cur < queue.size()) order.add(0, cur);
        opos = cur >= 0 ? 0 : -1;
    }

    public void jump(int i) {
        if (i < 0 || i >= queue.size()) return;
        cur = i; int k = order.indexOf(i); if (k >= 0) opos = k;
        start(true);
    }

    public void playNext(Song s) {
        if (now() == null) { playList(Collections.singletonList(s), 0); return; }
        int at = cur + 1; queue.add(at, s);
        for (int i = 0; i < order.size(); i++) if (order.get(i) >= at) order.set(i, order.get(i) + 1);
        order.add(Math.min(order.size(), opos + 1), at);
        save(); fire();
    }

    public void addToQueue(Song s) {
        if (now() == null) { playList(Collections.singletonList(s), 0); return; }
        queue.add(s); order.add(queue.size() - 1);
        save(); fire();
    }

    public void remove(Song s) {
        Song n = now(); int idx = queue.indexOf(s);
        while (idx >= 0) { queue.remove(idx); idx = queue.indexOf(s); }
        if (n == s) { release(); cur = -1; abandonFocus(); }
        else if (n != null) cur = queue.indexOf(n);
        buildOrder(); save(); fire();
    }

    // ---------- transport ----------
    void start(boolean play) {
        Song s = now(); if (s == null) return;
        release();
        try {
            mp = new MediaPlayer();
            if (session != 0) mp.setAudioSessionId(session);
            mp.setAudioAttributes(attrs());
            mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);   // keep going with the screen off
            mp.setDataSource(ctx, uri(s));
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer m) { completed(); } });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() { public boolean onError(MediaPlayer m, int w, int x) { failed(); return true; } });
            mp.prepare(); prepared = true; errors = 0;
            applyVol(); attachEq();
            if (play) {
                if (requestFocus()) { mp.start(); applySpeed(); noisy(true); ensureService(); }
                counts.edit().putInt(Long.toString(s.id), plays(s) + 1).apply();
                // Playing anything from the Underground unlocks the secret theme (the activity announces it).
                if (isToby(s) && !prefs.getBoolean("secret", false)) prefs.edit().putBoolean("secret", true).putBoolean("secretNew", true).apply();
            }
        } catch (Exception e) {
            prepared = false;
            Toast.makeText(ctx, "Can't play " + s.title, Toast.LENGTH_SHORT).show();
            if (play && ++errors < Math.min(queue.size(), 8)) { h.postDelayed(new Runnable() { public void run() { next(false); } }, 300); }
        }
        save(); fire();
    }

    void completed() {
        if (sleepEndOfSong) { sleepEndOfSong = false; seek(0); pause(); return; }
        if (repeat == 2) { seek(0); try { mp.start(); applySpeed(); } catch (Exception e) { } fire(); return; }
        next(false);
    }

    void failed() { release(); if (++errors < Math.min(queue.size(), 8)) next(false); else fire(); }

    public void play() {
        if (!loaded()) { if (now() != null) start(true); return; }
        if (!requestFocus()) return;
        try { mp.start(); applySpeed(); } catch (Exception e) { }
        noisy(true); ensureService(); fire();
    }

    public void pause() {
        resumeOnGain = false;
        try { if (playing()) mp.pause(); } catch (Exception e) { }
        noisy(false); save(); fire();
    }

    /** Returns false if there's nothing queued (caller should start something). */
    public boolean toggle() {
        if (now() == null) return false;
        if (playing()) pause(); else play();
        return true;
    }

    public void next(boolean user) {
        if (queue.isEmpty()) return;
        int n = nextIndex(user);
        if (n < 0) { seek(0); pause(); return; }
        cur = n; start(true);
    }

    int nextIndex(boolean wrap) {
        if (shuffle) {
            if (order.size() != queue.size()) buildOrder();
            if (opos + 1 < order.size()) return order.get(++opos);
            if (repeat == 1 || wrap) { Collections.shuffle(order); opos = 0; return order.get(0); }
            return -1;
        }
        if (cur + 1 < queue.size()) return cur + 1;
        return repeat == 1 || wrap ? 0 : -1;
    }

    public void prev() {
        if (queue.isEmpty()) return;
        if (pos() > 3000) { seek(0); return; }
        if (shuffle && opos > 0 && order.size() == queue.size()) cur = order.get(--opos);
        else cur = cur > 0 ? cur - 1 : queue.size() - 1;
        start(true);
    }

    public void seek(int ms) { try { if (loaded()) mp.seekTo(Math.max(0, Math.min(dur(), ms))); } catch (Exception e) { } fire(); }

    public void setShuffle(boolean s) { shuffle = s; if (s) buildOrder(); save(); fire(); }
    public void cycleRepeat() { repeat = (repeat + 1) % 3; save(); fire(); }
    public void setVol(float v) { vol = Math.max(0, Math.min(1, v)); applyVol(); prefs.edit().putFloat("vol", vol).apply(); }
    public void setSpeed(float s) { speed = s; prefs.edit().putFloat("speed", s).apply(); if (playing()) applySpeed(); fire(); }

    void applyVol() { try { if (mp != null) { float v = vol * duck * fade; mp.setVolume(v, v); } } catch (Exception e) { } }

    // setPlaybackParams starts a paused player, so only call this while playing.
    void applySpeed() {
        if (Build.VERSION.SDK_INT < 23 || mp == null) return;
        try { PlaybackParams pp = mp.getPlaybackParams(); if (Math.abs(pp.getSpeed() - speed) > .01f) mp.setPlaybackParams(pp.setSpeed(speed)); } catch (Throwable e) { }
    }

    void release() {
        if (mp != null) { try { mp.release(); } catch (Exception e) { } }
        mp = null; prepared = false;
    }

    /** "Exit": stop everything and drop the notification. */
    public void shutdown() {
        save(); pause(); release(); abandonFocus(); sleepAt = 0; fade = 1f;
        ctx.stopService(new Intent(ctx, PlaybackService.class));
        fire();
    }

    void ensureService() {
        if (PlaybackService.running) return;
        Intent i = new Intent(ctx, PlaybackService.class);
        try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i); } catch (Throwable e) { }
    }

    // ---------- audio focus + headphones unplugged ----------
    AudioAttributes attrs() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    final AudioManager.OnAudioFocusChangeListener afl = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int f) {
            if (f == AudioManager.AUDIOFOCUS_GAIN) { duck = 1f; applyVol(); if (resumeOnGain) { resumeOnGain = false; play(); } }
            else if (f == AudioManager.AUDIOFOCUS_LOSS) { focus = false; pause(); }
            else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (playing()) { try { mp.pause(); } catch (Exception e) { } resumeOnGain = true; noisy(false); fire(); } }
            else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { duck = .25f; applyVol(); }
        }
    };

    boolean requestFocus() {
        if (focus) return true;
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (afr == null) afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs()).setOnAudioFocusChangeListener(afl, h).build();
            r = am.requestAudioFocus(afr);
        } else r = am.requestAudioFocus(afl, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        focus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focus;
    }

    void abandonFocus() {
        if (!focus) return;
        if (Build.VERSION.SDK_INT >= 26 && afr != null) am.abandonAudioFocusRequest(afr); else am.abandonAudioFocus(afl);
        focus = false;
    }

    final BroadcastReceiver noisyRx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pause(); }
    };

    void noisy(boolean on) {
        if (on == noisyOn) return;
        try {
            if (on) {
                IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(noisyRx, f, 4 /* Context.RECEIVER_NOT_EXPORTED (API 33) */); else ctx.registerReceiver(noisyRx, f);
            } else ctx.unregisterReceiver(noisyRx);
            noisyOn = on;
        } catch (Throwable e) { noisyOn = false; }
    }

    // ---------- equalizer ----------
    void attachEq() {
        try {
            if (eqPreset < 0 || session == 0) { if (eq != null) eq.setEnabled(false); return; }
            if (eq == null) eq = new Equalizer(0, session);
            if (eqPreset < eq.getNumberOfPresets()) { eq.usePreset((short) eqPreset); eq.setEnabled(true); }
        } catch (Throwable e) { eq = null; }
    }

    public String[] eqPresets() {
        Equalizer e = null;
        try {
            e = eq != null ? eq : new Equalizer(0, session);
            short n = e.getNumberOfPresets(); String[] r = new String[n];
            for (short i = 0; i < n; i++) r[i] = e.getPresetName(i);
            return r;
        } catch (Throwable t) { return new String[0]; }
        finally { if (e != null && e != eq) try { e.release(); } catch (Throwable t) { } }
    }

    public void setEq(int preset) { eqPreset = preset; prefs.edit().putInt("eq", preset).apply(); attachEq(); }

    // ---------- sleep timer (fades out over the last 30 s) ----------
    public void setSleep(int minutes) {
        sleepEndOfSong = minutes < 0; sleepAt = minutes > 0 ? SystemClock.uptimeMillis() + minutes * 60000L : 0;
        fade = 1f; applyVol(); h.removeCallbacks(sleepTick); if (sleepAt > 0) h.post(sleepTick); fire();
    }

    public String sleepLabel() {
        if (sleepEndOfSong) return "End of song";
        if (sleepAt == 0) return "Off";
        long m = Math.max(0, sleepAt - SystemClock.uptimeMillis()) / 1000;
        return String.format(Locale.US, "%d:%02d left", m / 60, m % 60);
    }

    final Runnable sleepTick = new Runnable() { public void run() {
        if (sleepAt == 0) return;
        long left = sleepAt - SystemClock.uptimeMillis();
        if (left <= 0) { sleepAt = 0; pause(); fade = 1f; applyVol(); fire(); return; }
        fade = left < 30000 ? Math.max(.05f, left / 30000f) : 1f; applyVol();
        h.postDelayed(this, 500);
    }};

    // ---------- remember where you were ----------
    public void save() {
        StringBuilder b = new StringBuilder();
        int from = Math.max(0, cur - 1500), to = Math.min(queue.size(), from + 3000);
        for (int i = from; i < to; i++) { if (b.length() > 0) b.append(','); b.append(queue.get(i).id); }
        prefs.edit().putBoolean("shuffle", shuffle).putInt("repeat", repeat).putFloat("vol", vol)
            .putString("q", b.toString()).putInt("qcur", cur - from).putInt("qpos", pos() > 0 ? pos() : prefs.getInt("qpos", 0)).apply();
    }

    final Runnable autosave = new Runnable() { public void run() { if (playing()) { save(); syncListen(); } h.postDelayed(this, 10000); } };
    boolean autosaving;

    /** After the library loads: put back the last queue + song, paused at the same spot. */
    public void restore(List<Song> lib) {
        if (!autosaving) { autosaving = true; h.postDelayed(autosave, 10000); }
        if (!queue.isEmpty() || mp != null) return;
        String q = prefs.getString("q", ""); if (q.isEmpty()) return;
        HashMap<Long, Song> byId = new HashMap<>(); for (Song s : lib) byId.put(s.id, s);
        String[] ids = q.split(","); int want = prefs.getInt("qcur", 0), c = -1;
        ArrayList<Song> l = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            try { Song s = byId.get(Long.parseLong(ids[i])); if (s != null) { if (i == want) c = l.size(); l.add(s); } } catch (NumberFormatException e) { }
        }
        if (l.isEmpty() || c < 0) return;
        queue = l; cur = c; buildOrder();
        int at = prefs.getInt("qpos", 0);
        start(false);
        if (at > 0) seek(at);
    }

    // ---------- the music library (MediaStore) ----------
    static String clean(String s, String d) { return (s == null || s.isEmpty() || s.equals("<unknown>")) ? d : s; }

    static boolean canRead(Context c) {
        String p = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO" : "android.permission.READ_EXTERNAL_STORAGE";
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    static ArrayList<Song> query(Context ctx) throws Exception {
        ArrayList<Song> all = new ArrayList<>();
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TRACK};
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE");
            while (c != null && c.moveToNext()) {
                Song s = new Song(); s.id = c.getLong(0);
                s.title = clean(c.getString(1), "Untitled"); s.artist = clean(c.getString(2), "Unknown Artist");
                s.album = clean(c.getString(3), "Unknown Album"); s.dur = c.getInt(4); s.albumId = c.getLong(5);
                s.added = c.getLong(6); s.file = c.getString(7); s.track = c.getInt(8) % 1000; all.add(s);
            }
        } finally { if (c != null) c.close(); }
        return all;
    }

    /** With the app closed (widget / headset button): bring back the last queue so there's something to play. */
    public void restoreCold() {
        if (now() != null || !canRead(ctx)) return;
        try { restore(query(ctx)); } catch (Throwable e) { }
    }

    // ---------- album art (shared by the UI and the notification) ----------
    static Bitmap loadArt(Context ctx, Song s, int max) {
        Uri u = uri(s); Bitmap b = null;
        if (Build.VERSION.SDK_INT >= 29) try { b = ctx.getContentResolver().loadThumbnail(u, new Size(max, max), null); } catch (Throwable e) { b = null; }
        if (b == null) try {
            MediaMetadataRetriever r = new MediaMetadataRetriever(); r.setDataSource(ctx, u);
            byte[] x = r.getEmbeddedPicture(); r.release();
            if (x != null) {
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(x, 0, x.length, o);
                int sm = Math.max(1, Math.min(o.outWidth, o.outHeight) / max);
                o = new BitmapFactory.Options(); o.inSampleSize = sm;
                b = BitmapFactory.decodeByteArray(x, 0, x.length, o);
            }
        } catch (Throwable e) { b = null; }
        if (b == null) return null;
        int w = b.getWidth(), hh = b.getHeight(), sq = Math.min(w, hh);
        if (w != hh) b = Bitmap.createBitmap(b, (w - sq) / 2, (hh - sq) / 2, sq, sq);
        if (sq > max) b = Bitmap.createScaledBitmap(b, max, max, true);
        return b;
    }

    // ---------- the secret: Undertale / Deltarune detection ----------
    // Only titles distinctive enough not to match normal songs (no "Big Shot", "Don't Forget", "Heartache"...).
    static final String[] TOBY = {"megalovania", "bonetrousle", "spider dance", "death by glamour", "spear of justice", "battle against a true hero",
        "asgore", "your best nightmare", "snowdin", "nyeh heh heh", "metal crusher", "it's raining somewhere else", "bergentr", "rude buster",
        "field of hopes and dreams", "the world revolving", "chaos king", "vs. lancer", "attack of the killer queen", "pandora palace",
        "hammer of justice", "black knife", "you can always come home", "scarlet forest", "ruder buster", "flowey", "papyrus", "undyne",
        "mettaton", "toriel", "alphys", "spamton", "ralsei", "hotland", "my castle town", "raise up your bat", "core approach", "sans."};

    public static boolean isToby(Song s) {
        if (s == null) return false;
        String a = (s.title + "|" + s.artist + "|" + s.album + "|" + (s.file == null ? "" : s.file)).toLowerCase(Locale.US);
        if (a.contains("undertale") || a.contains("deltarune") || a.contains("toby fox") || a.contains("tobyfox")) return true;
        String t = s.title.toLowerCase(Locale.US);
        for (String k : TOBY) if (t.contains(k)) return true;
        return false;
    }
}
