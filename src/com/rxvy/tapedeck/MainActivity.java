package com.rxvy.tapedeck;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity implements Player.Listener {
    interface StrCb { void got(String s); }
    /** Views with a scrolling list implement this so filters can reset them. */
    interface Scrolly { void top(); }

    final ArrayList<Song> all = new ArrayList<>();
    Player pl;
    int skin; boolean monoFont;
    // Themes. The last one is a secret: it unlocks when you play something from Undertale or Deltarune.
    static final String[] TN = {"iPod Classic", "Windows XP", "Windows 95", "Game Boy", "Winamp", "Cassette", "iTunes 2011", "* ???"};
    static final int ITUNES = 6, SECRET = 7;
    Song pending; View view;
    final Random rnd = new Random();
    final Handler h = new Handler(Looper.getMainLooper());
    static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;
    static final String REPO = "https://github.com/rxvy-dev/tapedeck";

    // Song filter shared by every theme
    static final int F_ALL = 0, F_ARTIST = 1, F_ALBUM = 2, F_SEARCH = 3, F_FAV = 4, F_RECENT = 5, F_TOP = 6;
    int fk; String fv;
    final HashSet<Long> favs = new HashSet<>();
    int libVer;

    float d(float v) { return v * getResources().getDisplayMetrics().density; }
    SharedPreferences prefs() { return getSharedPreferences("MainActivity", 0); }   // same file getPreferences(0) used before

    @Override protected void onCreate(Bundle b) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(b);
        pl = Player.get(this);
        for (String s : prefs().getStringSet("favs", new HashSet<String>())) try { favs.add(Long.parseLong(s)); } catch (NumberFormatException e) { }
        loadUtFont();
        setSkin(savedSkin());
        askPermissions();
        handleExtras(getIntent());
        h.post(tick);
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleExtras(i); }

    @Override protected void onResume() {
        super.onResume();
        pl.listen(this);
        if (view != null) view.invalidate();
        announceSecret();
    }

    @Override protected void onPause() { pl.unlisten(this); super.onPause(); }
    @Override protected void onStop() { pl.save(); super.onStop(); }

    @Override public void changed() { if (view != null) view.invalidate(); announceSecret(); }

    // ---------- permissions ----------
    static String audioPerm() { return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO" : Manifest.permission.READ_EXTERNAL_STORAGE; }

    boolean has(String p) { return Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    void askPermissions() {
        ArrayList<String> want = new ArrayList<>();
        if (!has(audioPerm())) want.add(audioPerm());
        if (Build.VERSION.SDK_INT >= 33 && !has("android.permission.POST_NOTIFICATIONS") && !prefs().getBoolean("askedNotif", false)) {
            want.add("android.permission.POST_NOTIFICATIONS"); prefs().edit().putBoolean("askedNotif", true).apply();
        }
        if (has(audioPerm())) loadSongs();
        if (!want.isEmpty() && Build.VERSION.SDK_INT >= 23) requestPermissions(want.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        for (int i = 0; i < p.length; i++) {
            if (!p[i].equals(audioPerm())) continue;
            if (r[i] == PackageManager.PERMISSION_GRANTED) loadSongs();
            else new AlertDialog.Builder(this).setTitle("Music access needed").setMessage("Tape Deck plays the music files on your phone, so it needs permission to read them.")
                .setPositiveButton("Open Settings", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dl, int w) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception e) { }
                }}).setNegativeButton("Not now", null).show();
        }
    }

    // ---------- themes ----------
    int savedSkin() {
        String n = prefs().getString("skinName", TN[0]);
        if (n.equals("Classic Mac")) n = TN[ITUNES];   // Classic Mac was replaced by iTunes 2011
        for (int i = 0; i < TN.length; i++) if (TN[i].equals(n)) return i == SECRET && !prefs().getBoolean("secret", false) ? 0 : i;
        return 0;
    }

    void setSkin(int s) {
        if (s < 0 || s >= TN.length) s = 0;
        skin = s; prefs().edit().putString("skinName", TN[s]).apply();
        monoFont = s == 3 || s == 4 || s == SECRET;
        View v;
        if (s == 0) v = new IPod(this); else if (s == 1) v = new Xp(this); else if (s == ITUNES) v = new ITunes(this);
        else if (s == SECRET) v = new Ut(this); else v = new Retro(this, s);
        view = v; setContentView(v);
        int bar = s == 0 ? 0xFFEDEDF0 : s == 1 ? 0xFF0B4FC8 : (s == SECRET || s == ITUNES) ? BLACK : RP[s - 2][0];
        int nav = s == 0 ? 0xFFEDEDF0 : s == 1 ? 0xFF1941A5 : bar;
        getWindow().setStatusBarColor(bar); getWindow().setNavigationBarColor(nav);
        getWindow().getDecorView().setSystemUiVisibility(s == 0 || (s >= 2 && s < ITUNES && lum(bar) > 140) ? 0x2010 : 0);
    }

    static int lum(int c) { return (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000; }

    void pickTheme() {
        int n = prefs().getBoolean("secret", false) ? TN.length : TN.length - 1;
        new AlertDialog.Builder(this).setTitle("Theme").setItems(Arrays.copyOf(TN, n), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dl, int i) { setSkin(i); }
        }).show();
    }

    boolean announcing;
    void announceSecret() {
        if (announcing || !prefs().getBoolean("secretNew", false) || isFinishing()) return;
        announcing = true; prefs().edit().putBoolean("secretNew", false).apply();
        if (skin == SECRET) { announcing = false; return; }
        new AlertDialog.Builder(this).setTitle("* You are filled with DETERMINATION.")
            .setMessage("Music from the Underground echoes through the tape deck...\n\nA secret theme was unlocked. It has a FIGHT mode where the song attacks you to the beat.")
            .setPositiveButton("* FIGHT", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dl, int w) { setSkin(SECRET); } })
            .setNegativeButton("Later", null)
            .setOnDismissListener(new DialogInterface.OnDismissListener() { public void onDismiss(DialogInterface dl) { announcing = false; } }).show();
    }

    // For the screenshot robot (CI): am start ... --ei td_skin 1 --ei td_play 3 --ef td_seek .4 --ez td_fight true
    int demoPlay = -1; float demoSeek = .35f; boolean demoFight;
    void handleExtras(Intent it) {
        if (it == null) return;
        int want = it.getIntExtra("td_skin", -1);
        String th = it.getStringExtra("td_theme");
        if (th != null) for (int i = 0; i < TN.length; i++) if (TN[i].equalsIgnoreCase(th)) want = i;
        if (want >= 0 && want < TN.length) {
            if (want == SECRET) prefs().edit().putBoolean("secret", true).putBoolean("secretNew", false).apply();
            setSkin(want);
        }
        if (it.hasExtra("td_play")) { demoPlay = it.getIntExtra("td_play", 0); demoSeek = it.getFloatExtra("td_seek", .35f); demoFight = it.getBooleanExtra("td_fight", false); }
        if (demoPlay >= 0 && !all.isEmpty()) runDemo();
    }

    void runDemo() {
        final int i = Math.min(demoPlay, all.size() - 1); demoPlay = -1;
        pl.playList(all, i);
        h.postDelayed(new Runnable() { public void run() {
            pl.seek((int) (pl.dur() * demoSeek));
            if (demoFight && view instanceof Ut) ((Ut) view).startFight();
        }}, 400);
    }

    // ---------- Undertale font: bundled Determination (CC BY 3.0), or a font file you load ----------
    Typeface utFont = Typeface.MONOSPACE, logoFore, logoBack; int utGrid; String utName = "Monospace"; float utAdv = 1f;

    void loadUtFont() {
        Typeface t = null; utGrid = 0;
        try { File f = new File(getFilesDir(), "DeterminationMono.ttf"); if (f.exists()) { t = Typeface.createFromFile(f); utName = "Your font"; } } catch (Throwable e) { t = null; }
        if (t == null) try { t = Typeface.createFromAsset(getAssets(), "fonts/determination.ttf"); utGrid = 12; utName = "Determination"; } catch (Throwable e) { t = null; }
        if (t == null) try { t = Typeface.createFromAsset(getAssets(), "PressStart2P.ttf"); utGrid = 8; utName = "Press Start 2P"; } catch (Throwable e) { t = Typeface.MONOSPACE; utGrid = 0; }
        utFont = t;
        try { logoFore = Typeface.createFromAsset(getAssets(), "MonsterFriendFore.otf"); logoBack = Typeface.createFromAsset(getAssets(), "MonsterFriendBack.otf"); } catch (Throwable e) { logoFore = null; logoBack = null; }
        Paint m = new Paint(); m.setTypeface(t); m.setTextSize(120); utAdv = Math.max(.3f, m.measureText("M") / 120f);
    }

    void fontMenu() {
        final File f = new File(getFilesDir(), "DeterminationMono.ttf");
        pick(new String[]{"Determination (built in)", "Load a font file (.ttf / .zip)..."}, new Runnable[]{
            new Runnable() { public void run() { f.delete(); loadUtFont(); if (skin == SECRET) setSkin(SECRET); toast("* Determination."); } },
            new Runnable() { public void run() { pickFont(); } } });
    }

    void pickFont() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        try { startActivityForResult(i, 77); } catch (Exception e) { toast("No file picker found"); }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    void importFont(Uri u) {
        try {
            InputStream in = getContentResolver().openInputStream(u); byte[] data = readAll(in); in.close();
            if (data.length > 4 && data[0] == 'P' && data[1] == 'K') {
                ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data)); ZipEntry e; byte[] best = null; int bs = -1;
                while ((e = z.getNextEntry()) != null) {
                    String n = e.getName().toLowerCase(Locale.US);
                    if (n.endsWith(".ttf") || n.endsWith(".otf")) {
                        byte[] f = readAll(z); int sc = (n.contains("mono") ? 2 : 0) + (n.contains("determination") ? 1 : 0);
                        if (sc > bs) { best = f; bs = sc; }
                    }
                }
                if (best == null) throw new IOException("no .ttf inside that zip");
                data = best;
            }
            File f = new File(getFilesDir(), "DeterminationMono.ttf");
            FileOutputStream fo = new FileOutputStream(f); fo.write(data); fo.close();
            if (Build.VERSION.SDK_INT >= 26 && new Typeface.Builder(f).build() == null) { f.delete(); throw new IOException("that file isn't a usable font"); }
            loadUtFont();
            toast("* Font loaded.");
            if (skin == SECRET) setSkin(SECRET);
        } catch (Exception e) { toast("Font error: " + e.getMessage()); }
    }

    // ---------- album art (real covers from MediaStore / embedded tags, cached) ----------
    final LruCache<String, Bitmap> artCache = new LruCache<>(80), pixCache = new LruCache<>(160);
    final HashSet<String> artBusy = new HashSet<>(), artNone = new HashSet<>();
    final ExecutorService artEx = Executors.newFixedThreadPool(2), beatEx = Executors.newSingleThreadExecutor();

    static String artKey(Song s) { return s.albumId > 0 ? "a" + s.albumId : "s" + s.id; }

    /** Cover for a song, or null while it's loading / if it has none. Triggers a redraw when it arrives. */
    Bitmap artFor(Song s) {
        if (s == null) return null;
        final String k = artKey(s); Bitmap b = artCache.get(k);
        if (b != null) return b;
        if (artNone.contains(k) || !artBusy.add(k)) return null;
        final Song fs = s;
        try {
            artEx.execute(new Runnable() { public void run() {
                final Bitmap r = Player.loadArt(MainActivity.this, fs, 320);
                h.post(new Runnable() { public void run() {
                    if (r != null) artCache.put(k, r); else artNone.add(k);
                    artBusy.remove(k); if (view != null) view.invalidate();
                }});
            }});
        } catch (Exception e) { artBusy.remove(k); }
        return null;
    }

    static int qz(int v, float st) { return Math.max(0, Math.min(255, Math.round(Math.round(v / st) * st))); }

    /** Chunky n x n posterised version of a cover (the "sprite" look). */
    static Bitmap pixelate(Bitmap src, int n, int levels) {
        Bitmap mid = Bitmap.createScaledBitmap(src, n * 3, n * 3, true), sm = Bitmap.createScaledBitmap(mid, n, n, true);
        int[] px = new int[n * n]; sm.getPixels(px, 0, n, 0, 0, n, n); float st = 255f / (levels - 1);
        for (int i = 0; i < px.length; i++) { int c = px[i]; px[i] = 0xFF000000 | (qz((c >> 16) & 255, st) << 16) | (qz((c >> 8) & 255, st) << 8) | qz(c & 255, st); }
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); out.setPixels(px, 0, n, 0, 0, n, n); return out;
    }

    Bitmap pixArt(Song s, int n) {
        Bitmap a = artFor(s); if (a == null) return null;
        String k = artKey(s) + ":" + n; Bitmap b = pixCache.get(k);
        if (b == null) { b = pixelate(a, n, 6); pixCache.put(k, b); }
        return b;
    }

    // ---------- library ----------
    void loadSongs() {
        all.clear();
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TRACK};
        Cursor c = null;
        try {
            c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE");
            while (c != null && c.moveToNext()) {
                Song s = new Song(); s.id = c.getLong(0);
                s.title = clean(c.getString(1), "Untitled"); s.artist = clean(c.getString(2), "Unknown Artist");
                s.album = clean(c.getString(3), "Unknown Album"); s.dur = c.getInt(4); s.albumId = c.getLong(5);
                s.added = c.getLong(6); s.file = c.getString(7); s.track = c.getInt(8) % 1000; all.add(s);
            }
        } catch (Exception e) { toast("Scan error: " + e.getMessage()); }
        finally { if (c != null) c.close(); }
        artNone.clear(); libVer++;
        pl.restore(all);
        if (demoPlay >= 0 && !all.isEmpty()) runDemo();
        if (view != null) view.invalidate();
    }

    static String clean(String s, String d) { return (s == null || s.isEmpty() || s.equals("<unknown>")) ? d : s; }

    // names + one representative song per artist / album, rebuilt when the library changes
    int groupVer = -1; final ArrayList<String> artists = new ArrayList<>(), albums = new ArrayList<>();
    final HashMap<String, Song> artistRep = new HashMap<>(), albumRep = new HashMap<>(); final HashMap<String, Integer> artistCount = new HashMap<>();

    void groups() {
        if (groupVer == libVer) return;
        groupVer = libVer; artistRep.clear(); albumRep.clear(); artistCount.clear();
        TreeMap<String, String> ar = new TreeMap<>(String.CASE_INSENSITIVE_ORDER), al = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Song s : all) {
            if (!ar.containsKey(s.artist)) { ar.put(s.artist, s.artist); artistRep.put(s.artist.toLowerCase(Locale.ROOT), s); }
            if (!al.containsKey(s.album)) { al.put(s.album, s.album); albumRep.put(s.album.toLowerCase(Locale.ROOT), s); }
            String k = s.artist.toLowerCase(Locale.ROOT); Integer n = artistCount.get(k); artistCount.put(k, n == null ? 1 : n + 1);
        }
        artists.clear(); artists.addAll(ar.values()); albums.clear(); albums.addAll(al.values());
    }

    ArrayList<String> artistNames() { groups(); return artists; }
    ArrayList<String> albumNames() { groups(); return albums; }
    Song repOf(boolean artist, String name) { groups(); return (artist ? artistRep : albumRep).get(name.toLowerCase(Locale.ROOT)); }
    int songCount(String artist) { groups(); Integer n = artistCount.get(artist.toLowerCase(Locale.ROOT)); return n == null ? 0 : n; }

    // ---------- filters (cached: themes ask for the list every frame) ----------
    final HashMap<String, ArrayList<Song>> fcache = new HashMap<>(); int fcacheVer = -1;

    ArrayList<Song> filtered() {
        ArrayList<Song> l = filter(fk, fv);
        if (l.isEmpty() && (fk == F_ARTIST || fk == F_ALBUM) && !all.isEmpty()) { fk = F_ALL; fv = null; l = filter(fk, fv); }   // artist/album deleted
        return l;
    }

    ArrayList<Song> filter(int k, String v) {
        if (fcacheVer != libVer) { fcache.clear(); fcacheVer = libVer; }
        String key = k + "\u0001" + (v == null ? "" : v);
        ArrayList<Song> l = fcache.get(key);
        if (l != null) return l;
        l = new ArrayList<>();
        if (k == F_RECENT) {
            l.addAll(all);
            Collections.sort(l, new Comparator<Song>() { public int compare(Song a, Song b) { return Long.compare(b.added, a.added); } });
            while (l.size() > 100) l.remove(l.size() - 1);
        } else if (k == F_TOP) {
            for (Song s : all) if (pl.plays(s) > 0) l.add(s);
            Collections.sort(l, new Comparator<Song>() { public int compare(Song a, Song b) { return pl.plays(b) - pl.plays(a); } });
            while (l.size() > 100) l.remove(l.size() - 1);
        } else {
            String q = v == null ? "" : v.toLowerCase(Locale.ROOT).trim();
            for (Song s : all) {
                boolean ok = k == F_ALL || (k == F_ARTIST && s.artist.equalsIgnoreCase(v)) || (k == F_ALBUM && s.album.equalsIgnoreCase(v)) || (k == F_FAV && favs.contains(s.id))
                    || (k == F_SEARCH && (s.title + " " + s.artist + " " + s.album).toLowerCase(Locale.ROOT).contains(q));
                if (ok) l.add(s);
            }
            if (k == F_ALBUM) Collections.sort(l, new Comparator<Song>() { public int compare(Song a, Song b) { return a.track != b.track ? a.track - b.track : a.title.compareToIgnoreCase(b.title); } });
        }
        fcache.put(key, l);
        return l;
    }

    static String label(int k, String v) {
        switch (k) {
            case F_ARTIST: return v; case F_ALBUM: return v; case F_SEARCH: return "\"" + v + "\"";
            case F_FAV: return "Favorites"; case F_RECENT: return "Recently Added"; case F_TOP: return "Most Played";
            default: return "All Songs";
        }
    }

    String filterLabel() {
        String pre = fk == F_ARTIST ? "Artist: " : fk == F_ALBUM ? "Album: " : fk == F_SEARCH ? "Search: " : "";
        return pre + label(fk, fv);
    }

    void setFilter(int k, String v) {
        fk = k; fv = v;
        if (view instanceof IPod) ((IPod) view).showFilter();
        else if (view instanceof Scrolly) ((Scrolly) view).top();
        if (view != null) view.invalidate();
    }

    void pickFilter() {
        pick(new String[]{"All Songs", "Search...", "Artists...", "Albums...", "Favorites (" + favs.size() + ")", "Recently Added", "Most Played"}, new Runnable[]{
            new Runnable() { public void run() { setFilter(F_ALL, null); } },
            new Runnable() { public void run() { search(); } },
            new Runnable() { public void run() { pickName("Artist", artistNames(), F_ARTIST); } },
            new Runnable() { public void run() { pickName("Album", albumNames(), F_ALBUM); } },
            new Runnable() { public void run() { setFilter(F_FAV, null); } },
            new Runnable() { public void run() { setFilter(F_RECENT, null); } },
            new Runnable() { public void run() { setFilter(F_TOP, null); } } });
    }

    void pickName(String title, ArrayList<String> names, final int k) {
        if (names.isEmpty()) { toast("No music found"); return; }
        final String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(title).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dl, int i) { setFilter(k, items[i]); }
        }).show();
    }

    void search() { askText("Search", "Song, artist or album", new StrCb() { public void got(String s) { if (!s.trim().isEmpty()) setFilter(F_SEARCH, s.trim()); } }); }

    void askText(String title, String hint, final StrCb cb) {
        final EditText e = new EditText(this); e.setSingleLine(); e.setHint(hint); e.setInputType(InputType.TYPE_CLASS_TEXT); e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        FrameLayout box = new FrameLayout(this); int pd = (int) d(20); box.setPadding(pd, (int) d(8), pd, 0); box.addView(e);
        final AlertDialog dl = new AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d0, int w) { cb.got(e.getText().toString()); } })
            .setNegativeButton("Cancel", null).create();
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() { public boolean onEditorAction(TextView v, int a, KeyEvent ev) {
            dl.dismiss(); cb.got(e.getText().toString()); return true;
        }});
        dl.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dl.show(); e.requestFocus();
    }

    // ---------- favorites ----------
    boolean fav(Song s) { return s != null && favs.contains(s.id); }

    void toggleFav(Song s) {
        if (s == null) return;
        if (!favs.remove(s.id)) favs.add(s.id);
        HashSet<String> out = new HashSet<>(); for (Long id : favs) out.add(Long.toString(id));
        prefs().edit().putStringSet("favs", out).apply(); libVer++;
        toast(fav(s) ? "Added to Favorites" : "Removed from Favorites");
        if (view != null) view.invalidate();
    }

    // ---------- playback (the Player does the work; these keep the themes' code short) ----------
    Song now() { return pl.now(); }
    boolean playing() { return pl.playing(); }
    int pos() { return pl.pos(); }
    int dur() { return pl.dur(); }
    void playList(List<Song> l, int i) { pl.playList(l, i); }
    void toggle() {
        if (pl.toggle()) return;
        ArrayList<Song> l = filtered().isEmpty() ? all : filtered();
        if (l.isEmpty()) return;
        if (pl.shuffle) pl.shuffleAll(l); else pl.playList(l, 0);
    }
    void stop() { pl.pause(); pl.seek(0); }
    void next(boolean user) { pl.next(user); }
    void prev() { pl.prev(); }
    void seekTo(int ms) { pl.seek(ms); }
    void setVol(float v) { pl.setVol(v); }
    String repeatName() { return new String[]{"Off", "All", "One"}[pl.repeat]; }

    static String fmt(int ms) { int s = Math.max(0, ms) / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------- menus shared by every theme ----------
    void mainMenu() {
        final boolean ut = skin == SECRET;
        ArrayList<String> items = new ArrayList<>(); final ArrayList<Runnable> acts = new ArrayList<>();
        items.add("Theme: " + TN[skin]); acts.add(new Runnable() { public void run() { pickTheme(); } });
        items.add("Browse / Search..."); acts.add(new Runnable() { public void run() { pickFilter(); } });
        items.add("Up Next..."); acts.add(new Runnable() { public void run() { upNext(); } });
        items.add("Shuffle: " + (pl.shuffle ? "On" : "Off")); acts.add(new Runnable() { public void run() { pl.setShuffle(!pl.shuffle); } });
        items.add("Repeat: " + repeatName()); acts.add(new Runnable() { public void run() { pl.cycleRepeat(); } });
        items.add("Sleep Timer: " + pl.sleepLabel()); acts.add(new Runnable() { public void run() { sleepMenu(); } });
        items.add("Equalizer: " + eqName()); acts.add(new Runnable() { public void run() { eqMenu(); } });
        items.add("Speed: " + speedName()); acts.add(new Runnable() { public void run() { speedMenu(); } });
        if (ut) { items.add("Font: " + utName); acts.add(new Runnable() { public void run() { fontMenu(); } }); }
        items.add("Refresh Library"); acts.add(new Runnable() { public void run() { loadSongs(); toast(all.size() + " songs"); } });
        items.add("About Tape Deck"); acts.add(new Runnable() { public void run() { about(); } });
        items.add("Exit (stop music)"); acts.add(new Runnable() { public void run() { exitApp(); } });
        pick(items.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    void exitApp() { pl.shutdown(); if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask(); else finish(); }

    void upNext() {
        if (pl.now() == null) { toast("Nothing is playing"); return; }
        final ArrayList<Integer> idx = new ArrayList<>();
        if (pl.shuffle && pl.order.size() == pl.queue.size()) for (int k = Math.max(0, pl.opos); k < pl.order.size() && idx.size() < 150; k++) idx.add(pl.order.get(k));
        else for (int k = pl.cur; k < pl.queue.size() && idx.size() < 150; k++) idx.add(k);
        String[] items = new String[idx.size()];
        for (int i = 0; i < items.length; i++) { Song s = pl.queue.get(idx.get(i)); items[i] = (i == 0 ? "▶  " : i + ".  ") + s.title + "  —  " + s.artist; }
        new AlertDialog.Builder(this).setTitle("Up Next (" + pl.queue.size() + " in queue)").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dl, int i) { if (i > 0) pl.jump(idx.get(i)); }
        }).show();
    }

    void sleepMenu() {
        final int[] mins = {0, 15, 30, 45, 60, 90, -1};
        pick(new String[]{"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "1.5 hours", "When this song ends"}, new Runnable[]{
            sl(mins[0]), sl(mins[1]), sl(mins[2]), sl(mins[3]), sl(mins[4]), sl(mins[5]), sl(mins[6])});
    }

    Runnable sl(final int m) { return new Runnable() { public void run() { pl.setSleep(m); toast(m == 0 ? "Sleep timer off" : m < 0 ? "Stopping after this song" : "Music stops in " + m + " min (fades out)"); } }; }

    String[] eqP;   // preset names, looked up once (creating an Equalizer every frame would be slow)
    String[] presets() { if (eqP == null) eqP = pl.eqPresets(); return eqP; }

    String eqName() {
        if (pl.eqPreset < 0) return "Off";
        String[] p = presets(); return pl.eqPreset < p.length ? p[pl.eqPreset] : "Off";
    }

    void eqMenu() {
        final String[] p = presets();
        if (p.length == 0) { toast("This phone has no built-in equalizer"); return; }
        String[] items = new String[p.length + 1]; items[0] = "Off"; System.arraycopy(p, 0, items, 1, p.length);
        new AlertDialog.Builder(this).setTitle("Equalizer").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dl, int i) { pl.setEq(i - 1); }
        }).show();
    }

    String speedName() { return (pl.speed == (int) pl.speed ? String.format(Locale.US, "%.1f", pl.speed) : String.format(Locale.US, "%.2f", pl.speed)).replaceAll("0$", "") + "x"; }

    void speedMenu() {
        if (Build.VERSION.SDK_INT < 23) { toast("Needs Android 6 or newer"); return; }
        final float[] sp = {.75f, 1f, 1.25f, 1.5f, 2f};
        new AlertDialog.Builder(this).setTitle("Speed").setItems(new String[]{"0.75x", "1x (normal)", "1.25x", "1.5x", "2x"}, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dl, int i) { pl.setSpeed(sp[i]); }
        }).show();
    }

    void about() {
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { }
        new AlertDialog.Builder(this).setTitle("Tape Deck " + ver)
            .setMessage("A retro music player for the files on your phone. Free and open source (MIT).\n\n" + REPO.replace("https://", "")
                + "\n\n" + all.size() + " songs, " + artistNames().size() + " artists\n\nFonts: Determination by anonymous-1438277 (Fontstruct, CC BY 3.0), Press Start 2P (SIL OFL), Monster Friend.\n\nNot affiliated with Apple, Microsoft, Nintendo, Nullsoft or Toby Fox.")
            .setPositiveButton("GitHub", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dl, int w) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO))); } catch (Exception e) { }
            }}).setNegativeButton("Close", null).show();
    }

    // ---------- delete (system "move to trash" prompt on Android 11+) ----------
    void confirmDelete(final Song s) {
        if (s == null) return;
        new AlertDialog.Builder(this).setTitle("Delete song?").setMessage(s.title + "\n" + s.artist)
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dl, int w) { deleteSong(s); } })
            .setNegativeButton("Cancel", null).show();
    }

    void deleteSong(Song s) {
        pending = s;
        Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.app.PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), Collections.singletonList(u), true);
                startIntentSenderForResult(pi.getIntentSender(), 7, null, 0, 0, 0);
            } else { getContentResolver().delete(u, null, null); removeSong(s); }
        } catch (SecurityException e) {
            try {
                if (Build.VERSION.SDK_INT >= 29 && e instanceof android.app.RecoverableSecurityException)
                    startIntentSenderForResult(((android.app.RecoverableSecurityException) e).getUserAction().getActionIntent().getIntentSender(), 7, null, 0, 0, 0);
                else toast("Can't delete (no permission)");
            } catch (Exception e2) { toast("Delete failed"); }
        } catch (Exception e) { toast("Delete failed: " + e.getMessage()); }
    }

    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        super.onActivityResult(rq, rs, d);
        if (rq == 7 && rs == RESULT_OK && pending != null) removeSong(pending);
        if (rq == 7) pending = null;
        if (rq == 77 && rs == RESULT_OK && d != null && d.getData() != null) importFont(d.getData());
    }

    void removeSong(Song s) {
        all.remove(s); favs.remove(s.id); libVer++;
        pl.remove(s);
        if (view != null) view.invalidate();
    }

    void pick(String[] items, final Runnable[] acts) {
        new AlertDialog.Builder(this).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dl, int i) { if (i < acts.length && acts[i] != null) acts[i].run(); }
        }).show();
    }

    /** Long-press menu for a song. */
    void songMenu(final List<Song> l, final int i) {
        if (i < 0 || i >= l.size()) return;
        final Song s = l.get(i);
        new AlertDialog.Builder(this).setTitle(s.title).setItems(new String[]{"Play", "Play Next", "Add to Queue", fav(s) ? "Remove from Favorites" : "Add to Favorites",
            "Go to Artist: " + s.artist, "Go to Album: " + s.album, "Delete..."}, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dl, int w) {
                if (w == 0) playList(l, i);
                else if (w == 1) { pl.playNext(s); toast("Playing next"); }
                else if (w == 2) { pl.addToQueue(s); toast("Added to queue"); }
                else if (w == 3) toggleFav(s);
                else if (w == 4) setFilter(F_ARTIST, s.artist);
                else if (w == 5) setFilter(F_ALBUM, s.album);
                else confirmDelete(s);
            }
        }).show();
    }

    @Override public void onBackPressed() {
        if (view instanceof Ut && ((Ut) view).back()) return;
        if (view instanceof IPod && ((IPod) view).back()) return;
        if (view instanceof ITunes && ((ITunes) view).back()) return;
        if (fk != F_ALL && !(view instanceof IPod)) { setFilter(F_ALL, null); return; }
        moveTaskToBack(true);   // keep playing, like any music app
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null); artEx.shutdownNow(); beatEx.shutdownNow(); pl.unlisten(this);
        super.onDestroy();
    }

    final Runnable tick = new Runnable() { public void run() {
        if (view != null) view.invalidate();
        h.postDelayed(this, skin == SECRET || (skin >= 1 && skin != ITUNES && playing()) ? 40 : 250);
    }};

    // ---------- smooth fling scrolling for the lists ----------
    class Fling {
        VelocityTracker vt; float v; long t;
        void down(MotionEvent e) { v = 0; if (vt == null) vt = VelocityTracker.obtain(); else vt.clear(); vt.addMovement(e); }
        void move(MotionEvent e) { if (vt != null) vt.addMovement(e); }
        void up(MotionEvent e, boolean go, View w) {
            if (vt == null) return;
            vt.addMovement(e); vt.computeCurrentVelocity(1000);
            v = go ? -vt.getYVelocity() : 0; if (Math.abs(v) < d(200)) v = 0;
            t = SystemClock.uptimeMillis(); if (v != 0) w.postInvalidateOnAnimation();
        }
        void stop() { v = 0; }
        /** Call each frame: returns how far to scroll and keeps the animation going. */
        float step(View w) {
            if (v == 0) return 0;
            long n = SystemClock.uptimeMillis(); float dt = Math.min(.05f, (n - t) / 1000f); t = n;
            float dy = v * dt; v *= (float) Math.pow(.06, dt);
            if (Math.abs(v) < d(25)) v = 0; else w.postInvalidateOnAnimation();
            return dy;
        }
        /** Apply a fling step to a scroll offset and clamp it. */
        float apply(float off, float max, View w) {
            float o = off + step(w), c = Math.max(0, Math.min(max, o));
            if (c != o) v = 0;
            return c;
        }
    }

    // ---------- drawing helpers ----------
    static Shader lg(float x0, float y0, float x1, float y1, int... c) { return new LinearGradient(x0, y0, x1, y1, c, null, Shader.TileMode.CLAMP); }

    void txt(Canvas c, TextPaint p, String s, float x, float y, float size, int col, boolean bold, Paint.Align al, float maxW) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setTypeface(Typeface.create(monoFont ? Typeface.MONOSPACE : Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        p.setTextSize(size); p.setColor(col); p.setTextAlign(al);
        if (maxW > 0) s = TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString();
        c.drawText(s, x, y, p);
    }

    void tri(Canvas c, Paint p, float x, float y, float s, int dir) {
        Path ph = new Path(); ph.moveTo(x - dir * s / 2, y - s * .6f); ph.lineTo(x + dir * s / 2, y); ph.lineTo(x - dir * s / 2, y + s * .6f); ph.close(); c.drawPath(ph, p);
    }

    // =====================================================================
    //  iPod Classic skin
    // =====================================================================
    class IPod extends View {
        final TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final ArrayList<int[]> st = new ArrayList<>(); final ArrayList<String> sa = new ArrayList<>();
        final RectF lcd = new RectF(), tmp = new RectF();
        float wcx, wcy, wr, ir, th, rowH, listW, lastA, acc, x0, y0; int down = -1, tapRow = -1; boolean scrolled, lpFired;
        IPod(Context c) { super(c); push(0, null); }

        void push(int t, String a) { st.add(new int[]{t, 0, 0}); sa.add(a); }
        boolean back() { if (st.size() > 1) { st.remove(st.size() - 1); sa.remove(sa.size() - 1); invalidate(); return true; } return false; }
        int[] node() { return st.get(st.size() - 1); }
        String arg() { return sa.get(sa.size() - 1); }

        static final String SEP = "\u0001";
        String enc(int k, String v) { return k + SEP + (v == null ? "" : v); }
        int encK(String a) { return a == null ? F_ALL : Integer.parseInt(a.substring(0, a.indexOf(SEP))); }
        String encV(String a) { if (a == null) return null; String v = a.substring(a.indexOf(SEP) + 1); return v.isEmpty() ? null : v; }
        ArrayList<Song> songsOf(String a) { return filter(encK(a), encV(a)); }
        void showFilter() { push(3, enc(fk, fv)); invalidate(); }

        ArrayList<String> items(int t, String a) {
            ArrayList<String> r = new ArrayList<>();
            if (t == 0) { r.add("Music"); r.add("Shuffle Songs"); r.add("Now Playing"); r.add("Up Next"); r.add("Settings"); }
            else if (t == 1) { r.add("Artists"); r.add("Albums"); r.add("Songs"); r.add("Favorites"); r.add("Recently Added"); r.add("Most Played"); r.add("Search"); }
            else if (t == 2) r.addAll(artistNames());
            else if (t == 6) r.addAll(albumNames());
            else if (t == 3) for (Song s : songsOf(a)) r.add(s.title);
            else if (t == 4) { r.add("Theme: " + TN[skin]); r.add("Shuffle: " + (pl.shuffle ? "On" : "Off")); r.add("Repeat: " + repeatName()); r.add("Sleep Timer: " + pl.sleepLabel());
                r.add("Equalizer: " + eqName()); r.add("Speed: " + speedName()); r.add("Refresh Music"); r.add("About"); r.add("Exit"); }
            return r;
        }

        void act(int i) {
            int t = node()[0]; String a = arg();
            if (t == 0) {
                if (i == 0) push(1, null);
                else if (i == 1) { if (!all.isEmpty()) pl.shuffleAll(all); push(5, null); }
                else if (i == 2) push(5, null); else if (i == 3) upNext(); else push(4, null);
            }
            else if (t == 1) {
                if (i == 0) push(2, null); else if (i == 1) push(6, null); else if (i == 2) push(3, enc(F_ALL, null));
                else if (i == 3) push(3, enc(F_FAV, null)); else if (i == 4) push(3, enc(F_RECENT, null)); else if (i == 5) push(3, enc(F_TOP, null));
                else askText("Search", "Song, artist or album", new StrCb() { public void got(String s) { if (!s.trim().isEmpty()) { push(3, enc(F_SEARCH, s.trim())); invalidate(); } } });
            }
            else if (t == 2) { ArrayList<String> n = artistNames(); if (i < n.size()) push(3, enc(F_ARTIST, n.get(i))); }
            else if (t == 6) { ArrayList<String> n = albumNames(); if (i < n.size()) push(3, enc(F_ALBUM, n.get(i))); }
            else if (t == 3) { ArrayList<Song> l = songsOf(a); if (i < l.size()) { playList(l, i); push(5, null); } }
            else if (t == 4) {
                if (i == 0) pickTheme(); else if (i == 1) pl.setShuffle(!pl.shuffle); else if (i == 2) pl.cycleRepeat(); else if (i == 3) sleepMenu();
                else if (i == 4) eqMenu(); else if (i == 5) speedMenu(); else if (i == 6) loadSongs(); else if (i == 7) about(); else exitApp();
            }
            else toggle();
            invalidate();
        }

        void step(int dd) {
            int[] n = node();
            if (n[0] == 5) { seekTo(pos() + dd * 4000); return; }
            n[1] = Math.max(0, Math.min(Math.max(0, items(n[0], arg()).size() - 1), n[1] + dd));
        }

        void delPrompt(int i) {
            if (node()[0] != 3) return;
            ArrayList<Song> l = songsOf(arg()); if (i >= 0 && i < l.size()) songMenu(l, i);
        }

        void art(Canvas c, float l, float t, float r, float b, String seed) {
            Bitmap ab = artFor(now());
            if (ab != null) {
                p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(BLACK); c.drawRect(l, t, r, b, p);
                float sz = Math.min(r - l, b - t) - d(6), cx = (l + r) / 2, cy = (t + b) / 2;
                p.setFilterBitmap(true);
                c.drawBitmap(ab, new Rect(0, 0, ab.getWidth(), ab.getHeight()), new RectF(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2), p);
                p.setFilterBitmap(false);
                return;
            }
            int sd = seed.hashCode(); float cell = d(11); int cols = (int) ((r - l) / cell) + 1, rows = (int) ((b - t) / cell) + 1;
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0xFF2A1B4F); c.drawRect(l, t, r, b, p);
            int[] pal = {0xFF2B2060, 0xFF5B3A9E, 0xFFB57AD0, 0xFFF4E1F2, 0xFFFFFFFF, 0xFF3B2430, 0xFFE0701C, 0xFFF3C531};
            c.save(); c.clipRect(l, t, r, b);
            for (int j = 0; j < rows; j++) for (int i = 0; i < cols; i++) {
                int k = Math.min(pal.length - 1, (int) ((j + .5f) / rows * pal.length));
                int hh = (i * 73856093) ^ (j * 19349663) ^ sd;
                if ((hh & 7) == 0 && k > 0) k--; else if ((hh & 7) == 1 && k < pal.length - 1) k++;
                p.setColor(pal[k]); c.drawCircle(l + i * cell + cell / 2, t + j * cell + cell / 2, cell * .46f, p);
            }
            c.restore();
        }

        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            c.drawColor(0xFFEDEDF0);
            float bw = Math.min(W - d(20), (H - d(16)) / 1.67f), bh = bw * 1.67f, bx = (W - bw) / 2, by = (H - bh) / 2;
            p.reset(); p.setAntiAlias(true);
            tmp.set(bx, by, bx + bw, by + bh);
            p.setShader(lg(0, by, 0, by + bh, 0xFFF3F3F5, 0xFFD2D2D6, 0xFFE8E8EB)); c.drawRoundRect(tmp, bw * .1f, bw * .1f, p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1.5f)); p.setColor(0xFFB0B0B5); c.drawRoundRect(tmp, bw * .1f, bw * .1f, p); p.setStyle(Paint.Style.FILL);
            // screen
            float sl = bx + bw * .075f, sr = bx + bw * .925f, stp = by + bh * .045f, sb = stp + bh * .385f;
            tmp.set(sl, stp, sr, sb); p.setColor(0xFF161618); c.drawRoundRect(tmp, d(8), d(8), p);
            float m = d(4); lcd.set(sl + m, stp + m, sr - m, sb - m);
            int[] n = node(); int t = n[0]; String a = arg(); boolean art = t == 0 || t == 1;
            th = lcd.height() * .105f; rowH = (lcd.height() - th) / 8f; listW = art ? lcd.width() * .51f : lcd.width();
            c.save(); c.clipRect(lcd);
            p.setColor(WHITE); c.drawRect(lcd, p);
            p.setShader(lg(0, lcd.top, 0, lcd.top + th, 0xFFFFFFFF, 0xFFB9BBBF)); c.drawRect(lcd.left, lcd.top, lcd.left + listW, lcd.top + th, p); p.setShader(null);
            p.setColor(0xFF8F9195); c.drawRect(lcd.left, lcd.top + th - d(1), lcd.left + listW, lcd.top + th, p);
            String title = t == 0 ? "iPod" : t == 1 ? "Music" : t == 2 ? "Artists" : t == 6 ? "Albums" : t == 3 ? label(encK(a), encV(a)) : t == 4 ? "Settings" : "Now Playing";
            txt(c, p, title, lcd.left + d(8), lcd.top + th * .74f, th * .58f, BLACK, true, Paint.Align.LEFT, listW - d(64));
            if (playing()) { p.setColor(0xFF2D7FE0); tri(c, p, lcd.left + listW - d(44), lcd.top + th * .5f, th * .42f, 1); }
            float bl = lcd.left + listW - d(32), bt = lcd.top + th * .27f, bb = lcd.top + th * .73f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(0xFF555555); tmp.set(bl, bt, bl + d(22), bb); c.drawRoundRect(tmp, d(2), d(2), p);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF45C246); tmp.set(bl + d(1.5f), bt + d(1.5f), bl + d(20.5f), bb - d(1.5f)); c.drawRect(tmp, p);
            p.setColor(0xFF555555); c.drawRect(bl + d(22), bt + (bb - bt) * .3f, bl + d(24), bb - (bb - bt) * .3f, p);
            if (t != 5) {
                ArrayList<String> it = items(t, a);
                if (n[1] >= it.size()) n[1] = Math.max(0, it.size() - 1);
                if (n[1] < n[2]) n[2] = n[1]; if (n[1] >= n[2] + 8) n[2] = n[1] - 7;
                if (it.isEmpty()) txt(c, p, "No Music", lcd.left + listW / 2, lcd.centerY(), rowH * .6f, 0xFF666666, true, Paint.Align.CENTER, 0);
                for (int i = n[2]; i < Math.min(it.size(), n[2] + 8); i++) {
                    float y = lcd.top + th + (i - n[2]) * rowH; boolean sel = i == n[1];
                    if (sel) { p.setShader(lg(0, y, 0, y + rowH, 0xFF55A3F0, 0xFF1B69C7)); c.drawRect(lcd.left, y, lcd.left + listW, y + rowH, p); p.setShader(null); }
                    txt(c, p, it.get(i), lcd.left + d(8), y + rowH * .72f, rowH * .56f, sel ? WHITE : BLACK, true, Paint.Align.LEFT, listW - d(30));
                    if (t <= 2 || t == 6) {
                        float cx = lcd.left + listW - d(12), cy = y + rowH / 2, s = rowH * .22f;
                        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(2.2f)); p.setColor(sel ? WHITE : 0xFF222222);
                        Path ph = new Path(); ph.moveTo(cx - s / 2, cy - s); ph.lineTo(cx + s / 2, cy); ph.lineTo(cx - s / 2, cy + s); c.drawPath(ph, p); p.setStyle(Paint.Style.FILL);
                    }
                }
                if (it.size() > 8) {
                    float tr = rowH * 8, kn = tr * 8 / it.size(), ky = lcd.top + th + tr * n[2] / it.size();
                    p.setColor(0x77000000); c.drawRect(lcd.left + listW - d(5), ky, lcd.left + listW - d(2), ky + kn, p);
                }
            } else {
                Song s = now(); float x = lcd.left + d(10), y = lcd.top + th;
                if (s == null) txt(c, p, "No Song Playing", lcd.centerX(), lcd.centerY(), rowH * .6f, 0xFF555555, true, Paint.Align.CENTER, 0);
                else {
                    txt(c, p, (pl.cur + 1) + " of " + pl.queue.size(), x, y + rowH * .9f, rowH * .5f, 0xFF666666, false, Paint.Align.LEFT, 0);
                    txt(c, p, s.title, x, y + rowH * 2.1f, rowH * .64f, BLACK, true, Paint.Align.LEFT, lcd.width() - d(20));
                    txt(c, p, s.artist, x, y + rowH * 3.1f, rowH * .56f, 0xFF222222, true, Paint.Align.LEFT, lcd.width() - d(20));
                    txt(c, p, s.album, x, y + rowH * 4.1f, rowH * .52f, 0xFF777777, false, Paint.Align.LEFT, lcd.width() - d(20));
                    float x1 = lcd.right - d(10), yb = lcd.bottom - rowH * 1.55f, hb = rowH * .38f; int du = dur(), ps = pos();
                    p.setColor(0xFFC9C9CD); tmp.set(x, yb, x1, yb + hb); c.drawRect(tmp, p);
                    p.setShader(lg(0, yb, 0, yb + hb, 0xFF6FB2F5, 0xFF1D6FD1)); c.drawRect(x, yb, x + (x1 - x) * (du > 0 ? Math.min(1f, ps / (float) du) : 0), yb + hb, p); p.setShader(null);
                    txt(c, p, fmt(ps), x, lcd.bottom - rowH * .35f, rowH * .5f, 0xFF333333, false, Paint.Align.LEFT, 0);
                    txt(c, p, "-" + fmt(du - ps), x1, lcd.bottom - rowH * .35f, rowH * .5f, 0xFF333333, false, Paint.Align.RIGHT, 0);
                }
            }
            if (art) { Song s = now(); art(c, lcd.left + listW, lcd.top, lcd.right, lcd.bottom, s == null ? "tapedeck" : s.title); }
            c.restore();
            // click wheel
            wcx = bx + bw * .5f; wcy = by + bh * .706f; wr = bw * .307f; ir = bw * .11f;
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0xFFFAFBFC); c.drawCircle(wcx, wcy, wr, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1.2f)); p.setColor(0xFFD7D8DC); c.drawCircle(wcx, wcy, wr, p);
            p.setStyle(Paint.Style.FILL); p.setColor(down == 1 ? 0xFFD9D9DD : 0xFFEFEFF2); c.drawCircle(wcx, wcy, ir, p);
            p.setStyle(Paint.Style.STROKE); p.setColor(0xFFD0D0D4); c.drawCircle(wcx, wcy, ir, p); p.setStyle(Paint.Style.FILL);
            // wheel labels: MENU top, prev left, next right, play/pause bottom
            float u = wr * .13f, off = wr * .66f;
            txt(c, p, "MENU", wcx, wcy - off + u * .4f, u * 1.1f, 0xFF8A8B90, true, Paint.Align.CENTER, 0);
            p.setColor(0xFF8A8B90);
            tri(c, p, wcx - off - u * .5f, wcy, u, -1); tri(c, p, wcx - off + u * .5f, wcy, u, -1);
            tri(c, p, wcx + off - u * .5f, wcy, u, 1); tri(c, p, wcx + off + u * .5f, wcy, u, 1);
            tri(c, p, wcx - u * .9f, wcy + off, u, 1);
            c.drawRect(wcx + u * .2f, wcy + off - u * .6f, wcx + u * .5f, wcy + off + u * .6f, p);
            c.drawRect(wcx + u * .8f, wcy + off - u * .6f, wcx + u * 1.1f, wcy + off + u * .6f, p);
        }

        final Runnable lp = new Runnable() { public void run() {
            int[] n = node();
            if (n[0] != 3) return;
            if (down == 1) { lpFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); delPrompt(n[1]); }
            else if (down == 2 && tapRow >= 0) { lpFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); delPrompt(tapRow); }
        }};

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY(), dx = x - wcx, dy = y - wcy, dist = (float) Math.sqrt(dx * dx + dy * dy);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    scrolled = false; lpFired = false; acc = 0; down = -1; tapRow = -1; x0 = x; y0 = y;
                    int[] n = node();
                    if (dist <= ir) down = 1;
                    else if (dist <= wr) { down = 0; lastA = (float) Math.atan2(dy, dx); }
                    else if (lcd.contains(x, y)) {
                        down = 2;
                        if (n[0] == 5) tapRow = 0;
                        else if (y > lcd.top + th && x < lcd.left + listW) {
                            int row = n[2] + (int) ((y - lcd.top - th) / rowH);
                            if (row >= 0 && row < items(n[0], arg()).size()) tapRow = row;
                        }
                    }
                    if (down >= 0) postDelayed(lp, ViewConfiguration.getLongPressTimeout());
                    invalidate(); return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (down == 0) {
                        float ang = (float) Math.atan2(dy, dx), da = ang - lastA;
                        if (da > Math.PI) da -= 2 * Math.PI; else if (da < -Math.PI) da += 2 * Math.PI;
                        lastA = ang; acc += da;
                        float thr = .42f;
                        while (acc > thr) { acc -= thr; step(1); scrolled = true; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }
                        while (acc < -thr) { acc += thr; step(-1); scrolled = true; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }
                        if (scrolled) removeCallbacks(lp);
                    } else if (down == 2 && (Math.abs(x - x0) > d(12) || Math.abs(y - y0) > d(12))) {
                        down = -1; removeCallbacks(lp);
                    }
                    invalidate(); return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    removeCallbacks(lp);
                    int dn = down; down = -1;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && !lpFired) {
                        if (dn == 1) act(node()[1]);
                        else if (dn == 0 && !scrolled) {
                            if (Math.abs(dx) > Math.abs(dy)) { if (dx < 0) prev(); else next(true); }
                            else if (dy < 0) back();
                            else toggle();
                        }
                        else if (dn == 2 && tapRow >= 0) { node()[1] = tapRow; act(tapRow); }
                    }
                    invalidate(); return true;
                }
            }
            return true;
        }
    }

    // =====================================================================
    //  Windows XP skin
    // =====================================================================
    class Xp extends View implements Scrolly {
        final TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF tmp = new RectF(), rClose = new RectF(), rArt = new RectF(), rShuf = new RectF(), rRep = new RectF(),
            rSeek = new RectF(), rPrev = new RectF(), rPlay = new RectF(), rStop = new RectF(), rNext = new RectF(),
            rVol = new RectF(), rList = new RectF(), rStart = new RectF();
        RectF pressed;
        final float[] lv = new float[28];
        final SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);
        float off, rowH, hdrB, downX, downY, lastY, seekF = -1; int mode; boolean moved, lpFired;
        final Fling fl = new Fling();
        Xp(Context c) { super(c); }
        public void top() { off = 0; fl.stop(); }

        ArrayList<Song> songs() { return filtered(); }
        boolean hit(RectF r, float x, float y, float pad) { return x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad; }
        void ctr(Canvas c, String s, RectF r, float size, int col, boolean bold) { txt(c, p, s, r.centerX(), r.centerY() + size * .36f, size, col, bold, Paint.Align.CENTER, r.width() - d(6)); }
        void arrow(Canvas c, float cx, float cy, float s) {
            Path ph = new Path(); ph.moveTo(cx - s, cy - s * .5f); ph.lineTo(cx + s, cy - s * .5f); ph.lineTo(cx, cy + s * .6f); ph.close(); c.drawPath(ph, p);
        }

        void bevel(Canvas c, RectF r, boolean on) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(lg(0, r.top, 0, r.bottom, on ? 0xFFD6D2BF : 0xFFFFFFFF, on ? 0xFFEDEADB : 0xFFE3E0D0));
            c.drawRoundRect(r, d(3), d(3), p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(0xFF003C74);
            c.drawRoundRect(r, d(3), d(3), p); p.setStyle(Paint.Style.FILL);
        }

        void sunk(Canvas c, float l, float t, float r, float b, int fill) {
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(fill); c.drawRect(l, t, r, b, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(0xFF7F9DB9); c.drawRect(l, t, r, b, p); p.setStyle(Paint.Style.FILL);
        }

        void winBtn(Canvas c, RectF r, int kind) {
            boolean red = kind == 2;
            p.setStyle(Paint.Style.FILL); p.setShader(lg(0, r.top, 0, r.bottom, red ? 0xFFE9886F : 0xFF6FA6FA, red ? 0xFFC03A1D : 0xFF1F5FD8));
            c.drawRoundRect(r, d(4), d(4), p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1.2f)); p.setColor(WHITE); c.drawRoundRect(r, d(4), d(4), p);
            float cx = r.centerX(), cy = r.centerY(), g = r.width() * .22f; p.setStrokeWidth(d(2));
            if (kind == 0) c.drawLine(cx - g, cy + g, cx + g, cy + g, p);
            else if (kind == 1) { p.setStrokeWidth(d(1.5f)); c.drawRect(cx - g, cy - g, cx + g, cy + g, p); }
            else { c.drawLine(cx - g, cy - g, cx + g, cy + g, p); c.drawLine(cx - g, cy + g, cx + g, cy - g, p); }
            p.setStyle(Paint.Style.FILL);
        }

        void rbtn(Canvas c, RectF r, int c0, int c1, boolean prs) {
            float rad = r.width() / 2;
            p.setStyle(Paint.Style.FILL); p.setShader(lg(0, r.top, 0, r.bottom, prs ? c1 : c0, prs ? c0 : c1));
            c.drawCircle(r.centerX(), r.centerY(), rad, p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1.5f)); p.setColor(0xFF1B3F8B);
            c.drawCircle(r.centerX(), r.centerY(), rad - d(.75f), p); p.setStyle(Paint.Style.FILL);
        }

        void thumb(Canvas c, float x, float y) { tmp.set(x - d(6), y - d(11), x + d(6), y + d(11)); bevel(c, tmp, false); }

        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), m = d(8), tbH = d(34), barH = d(38);
            p.reset(); p.setAntiAlias(true);
            c.drawColor(0xFFECE9D8);
            ArrayList<Song> l = songs(); Song np = now();

            // ----- title bar -----
            p.setShader(lg(0, 0, 0, tbH, 0xFF3D8DF9, 0xFF0A55DD, 0xFF0846B8)); c.drawRect(0, 0, W, tbH, p); p.setShader(null);
            txt(c, p, "Tape Deck", d(12), tbH / 2 + d(5), d(15), WHITE, true, Paint.Align.LEFT, W - d(110));
            float bs0 = d(22), by0 = (tbH - bs0) / 2;
            rClose.set(W - d(8) - bs0, by0, W - d(8), by0 + bs0);
            tmp.set(rClose.left - bs0 * 2 - d(6), by0, rClose.left - bs0 - d(6), by0 + bs0); winBtn(c, tmp, 0);
            tmp.set(rClose.left - bs0 - d(3), by0, rClose.left - d(3), by0 + bs0); winBtn(c, tmp, 1);
            winBtn(c, rClose, 2);
            p.setColor(0xFF0A3FB0); c.drawRect(0, tbH, d(3), H, p); c.drawRect(W - d(3), tbH, W, H, p);

            // ----- toolbar: artist picker, shuffle, repeat -----
            float y0 = tbH + d(7), y1 = y0 + d(30);
            rArt.set(m, y0, m + (W - 2 * m) * .46f, y1);
            float bw2 = (W - m - rArt.right - 2 * m) / 2;
            rShuf.set(rArt.right + m, y0, rArt.right + m + bw2, y1);
            rRep.set(rShuf.right + m, y0, rShuf.right + m + bw2, y1);
            bevel(c, rArt, pressed == rArt);
            txt(c, p, filterLabel(), rArt.left + d(8), rArt.centerY() + d(4.5f), d(13), BLACK, false, Paint.Align.LEFT, rArt.width() - d(28));
            p.setColor(BLACK); arrow(c, rArt.right - d(12), rArt.centerY(), d(4));
            bevel(c, rShuf, pl.shuffle || pressed == rShuf); ctr(c, "Shuffle", rShuf, d(13), pl.shuffle ? 0xFF0A3FB0 : BLACK, pl.shuffle);
            bevel(c, rRep, pl.repeat != 0 || pressed == rRep); ctr(c, "Repeat: " + repeatName(), rRep, d(13), pl.repeat != 0 ? 0xFF0A3FB0 : BLACK, pl.repeat != 0);

            // ----- visualizer -----
            float vt = y1 + d(8), vb = vt + d(100);
            p.setShader(lg(0, vt, 0, vb, 0xFF000A1F, 0xFF001C4D)); c.drawRect(m, vt, W - m, vb, p);
            boolean on = playing(); int nb = lv.length; float gw = (W - 2 * m - d(8)) / nb;
            p.setShader(lg(0, vt, 0, vb, 0xFFB8F2FF, 0xFF2B7BFF, 0xFF0A2A9E));
            for (int i = 0; i < nb; i++) {
                float tg = on ? (float) Math.pow(rnd.nextFloat(), 1.3) * (1f - i * .012f) : 0;
                lv[i] = lv[i] * .55f + tg * .45f;
                float bh = (vb - vt - d(8)) * lv[i];
                if (bh > d(1)) c.drawRect(m + d(4) + i * gw + d(1), vb - d(4) - bh, m + d(4) + (i + 1) * gw - d(1), vb - d(4), p);
            }
            p.setShader(null);
            Bitmap xab = artFor(np); float tw = W - 2 * m - d(20) - (xab != null ? d(92) : 0);
            if (np == null) txt(c, p, "Tap a song to play", m + d(10), vt + d(22), d(13), 0xFF9CC3FF, true, Paint.Align.LEFT, W - 2 * m - d(20));
            else {
                txt(c, p, np.title, m + d(10), vt + d(22), d(15), WHITE, true, Paint.Align.LEFT, tw);
                txt(c, p, np.artist, m + d(10), vt + d(40), d(12), 0xFFB4D0FF, false, Paint.Align.LEFT, tw);
                if (xab != null) {
                    float as = d(84); tmp.set(W - m - d(8) - as, vt + d(8), W - m - d(8), vt + d(8) + as);
                    p.setFilterBitmap(true); c.drawBitmap(xab, new Rect(0, 0, xab.getWidth(), xab.getHeight()), tmp, p); p.setFilterBitmap(false);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(2)); p.setColor(0xFFB8D4FF); c.drawRect(tmp, p); p.setStyle(Paint.Style.FILL);
                }
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(0xFF7F9DB9); c.drawRect(m, vt, W - m, vb, p); p.setStyle(Paint.Style.FILL);

            // ----- seek bar -----
            float st = vb + d(10); rSeek.set(m + d(42), st + d(4), W - m - d(42), st + d(16));
            int du = dur(), ps = pos();
            float f = seekF >= 0 ? seekF : (du > 0 ? Math.min(1f, ps / (float) du) : 0);
            if (seekF >= 0) ps = (int) (seekF * du);
            txt(c, p, fmt(ps), m, st + d(15), d(12), 0xFF222222, false, Paint.Align.LEFT, 0);
            txt(c, p, fmt(du), W - m, st + d(15), d(12), 0xFF222222, false, Paint.Align.RIGHT, 0);
            p.setColor(0xFFB9B5A0); c.drawRoundRect(rSeek, d(3), d(3), p);
            p.setShader(lg(0, rSeek.top, 0, rSeek.bottom, 0xFF7FB2FF, 0xFF1F5FD8));
            c.drawRoundRect(rSeek.left, rSeek.top, rSeek.left + Math.max(d(3), rSeek.width() * f), rSeek.bottom, d(3), d(3), p); p.setShader(null);
            thumb(c, rSeek.left + rSeek.width() * f, rSeek.centerY());

            // ----- transport + volume -----
            float tt = st + d(30), bs = d(40), gs = bs * .3f;
            rPrev.set(m, tt, m + bs, tt + bs);
            rPlay.set(rPrev.right + d(8), tt - d(3), rPrev.right + d(8) + bs + d(6), tt + bs + d(3));
            rStop.set(rPlay.right + d(8), tt, rPlay.right + d(8) + bs, tt + bs);
            rNext.set(rStop.right + d(8), tt, rStop.right + d(8) + bs, tt + bs);
            rbtn(c, rPrev, 0xFF7DB4FF, 0xFF0A3FB0, pressed == rPrev);
            rbtn(c, rPlay, 0xFF86E086, 0xFF1F8F1F, pressed == rPlay);
            rbtn(c, rStop, 0xFF7DB4FF, 0xFF0A3FB0, pressed == rStop);
            rbtn(c, rNext, 0xFF7DB4FF, 0xFF0A3FB0, pressed == rNext);
            p.setColor(WHITE);
            float cx = rPrev.centerX(), cy = rPrev.centerY();
            c.drawRect(cx - gs * .7f, cy - gs * .6f, cx - gs * .5f, cy + gs * .6f, p); tri(c, p, cx + gs * .1f, cy, gs, -1);
            cx = rNext.centerX(); cy = rNext.centerY();
            tri(c, p, cx - gs * .1f, cy, gs, 1); c.drawRect(cx + gs * .5f, cy - gs * .6f, cx + gs * .7f, cy + gs * .6f, p);
            cx = rStop.centerX(); cy = rStop.centerY(); c.drawRect(cx - gs * .45f, cy - gs * .45f, cx + gs * .45f, cy + gs * .45f, p);
            cx = rPlay.centerX(); cy = rPlay.centerY();
            if (on) { c.drawRect(cx - gs * .5f, cy - gs * .6f, cx - gs * .15f, cy + gs * .6f, p); c.drawRect(cx + gs * .15f, cy - gs * .6f, cx + gs * .5f, cy + gs * .6f, p); }
            else tri(c, p, cx + gs * .1f, cy, gs * 1.1f, 1);
            float vx0 = rNext.right + d(14);
            txt(c, p, "Vol", vx0, tt + bs / 2 + d(4), d(12), 0xFF222222, false, Paint.Align.LEFT, 0);
            rVol.set(vx0 + d(28), tt + bs / 2 - d(5), W - m - d(6), tt + bs / 2 + d(5));
            p.setColor(0xFFB9B5A0); c.drawRoundRect(rVol, d(3), d(3), p);
            p.setShader(lg(0, rVol.top, 0, rVol.bottom, 0xFF7FB2FF, 0xFF1F5FD8));
            c.drawRoundRect(rVol.left, rVol.top, rVol.left + Math.max(d(3), rVol.width() * pl.vol), rVol.bottom, d(3), d(3), p); p.setShader(null);
            thumb(c, rVol.left + rVol.width() * pl.vol, rVol.centerY());

            // ----- playlist -----
            float lt = tt + bs + d(14), lb = H - barH - m;
            rList.set(m, lt, W - m, lb);
            sunk(c, rList.left, rList.top, rList.right, rList.bottom, WHITE);
            hdrB = lt + d(22); rowH = d(30);
            p.setShader(lg(0, lt, 0, hdrB, 0xFFF7F6EF, 0xFFD9D5C3)); c.drawRect(rList.left + 1, lt + 1, rList.right - 1, hdrB, p); p.setShader(null);
            float colX = rList.left + rList.width() * .56f;
            txt(c, p, "Title", rList.left + d(20), lt + d(15), d(12), 0xFF222222, true, Paint.Align.LEFT, 0);
            txt(c, p, "Artist", colX, lt + d(15), d(12), 0xFF222222, true, Paint.Align.LEFT, 0);
            txt(c, p, "Time", rList.right - d(8), lt + d(15), d(12), 0xFF222222, true, Paint.Align.RIGHT, 0);
            float viewH = lb - hdrB, maxOff = Math.max(0, l.size() * rowH - viewH);
            off = fl.apply(off, maxOff, this);
            c.save(); c.clipRect(rList.left + 1, hdrB, rList.right - 1, lb - 1);
            if (l.isEmpty()) {
                txt(c, p, "No music found", rList.centerX(), hdrB + d(40), d(14), 0xFF444444, true, Paint.Align.CENTER, 0);
                txt(c, p, fk == F_ALL ? "Start > Refresh Library" : "Tap the filter box to change it", rList.centerX(), hdrB + d(60), d(12), 0xFF777777, false, Paint.Align.CENTER, 0);
            } else for (int i = Math.max(0, (int) (off / rowH)); i < l.size(); i++) {
                float ry = hdrB + i * rowH - off; if (ry > lb) break;
                Song s = l.get(i); boolean cu = s.equals(np);
                p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(cu ? 0xFF316AC5 : (i % 2 == 0 ? WHITE : 0xFFF3F6FD)); c.drawRect(rList.left + 1, ry, rList.right - 1, ry + rowH, p);
                int tc = cu ? WHITE : BLACK;
                if (cu) { p.setColor(WHITE); tri(c, p, rList.left + d(11), ry + rowH / 2, d(8), 1); }
                txt(c, p, s.title, rList.left + d(20), ry + rowH * .64f, d(13), tc, false, Paint.Align.LEFT, colX - rList.left - d(26));
                txt(c, p, s.artist, colX, ry + rowH * .64f, d(13), cu ? 0xFFDCE8FF : 0xFF444444, false, Paint.Align.LEFT, rList.right - colX - d(56));
                txt(c, p, fmt(s.dur), rList.right - d(8), ry + rowH * .64f, d(12), cu ? 0xFFDCE8FF : 0xFF444444, false, Paint.Align.RIGHT, 0);
            }
            if (maxOff > 0) {
                float tk = Math.max(d(24), viewH * viewH / (l.size() * rowH)), ty = hdrB + (viewH - tk) * off / maxOff;
                p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x66000000); c.drawRect(rList.right - d(5), ty, rList.right - d(2), ty + tk, p);
            }
            c.restore();

            // ----- taskbar -----
            float bt = H - barH;
            p.setShader(lg(0, bt, 0, H, 0xFF3C80F0, 0xFF245EDC, 0xFF1941A5)); c.drawRect(0, bt, W, H, p); p.setShader(null);
            rStart.set(0, bt, d(92), H);
            p.setShader(lg(0, bt, 0, H, 0xFF5CC55C, 0xFF2E8B2E)); tmp.set(-d(14), bt + d(1), d(92), H - d(1)); c.drawRoundRect(tmp, d(16), d(16), p); p.setShader(null);
            if (pressed == rStart) { p.setColor(0x33000000); c.drawRoundRect(tmp, d(16), d(16), p); }
            float fx = d(10), fy = bt + barH / 2 - d(8); int[] fc = {0xFFF35325, 0xFF81BC06, 0xFF05A6F0, 0xFFFFBA08};
            for (int i = 0; i < 4; i++) { p.setColor(fc[i]); float qx = fx + (i % 2) * d(8.5f), qy = fy + (i / 2) * d(8.5f); c.drawRect(qx, qy, qx + d(7.5f), qy + d(7.5f), p); }
            txt(c, p, "start", d(34), bt + barH / 2 + d(6), d(18), WHITE, true, Paint.Align.LEFT, 0);
            tmp.set(d(100), bt + d(5), W - d(86), H - d(5)); p.setColor(0xFF1E52B8); c.drawRoundRect(tmp, d(3), d(3), p);
            txt(c, p, np == null ? "Tape Deck" : np.title, tmp.left + d(8), tmp.centerY() + d(4.5f), d(13), WHITE, false, Paint.Align.LEFT, tmp.width() - d(16));
            tmp.set(W - d(80), bt, W, H); p.setShader(lg(0, bt, 0, H, 0xFF19A0F2, 0xFF0F7AD9)); c.drawRect(tmp, p); p.setShader(null);
            txt(c, p, clock.format(new Date()), tmp.centerX(), tmp.centerY() + d(4.5f), d(12), WHITE, false, Paint.Align.CENTER, 0);
        }

        // ----- list taps -----
        int rowAt(float y) {
            if (y < hdrB) return -1;
            int i = (int) ((y - hdrB + off) / rowH);
            return i >= 0 && i < songs().size() ? i : -1;
        }

        final Runnable lp = new Runnable() { public void run() {
            if (mode != 1 || moved) return;
            final ArrayList<Song> l = songs(); final int i = rowAt(downY); if (i < 0) return;
            lpFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            songMenu(l, i);
        }};

        void press(RectF r) {
            if (r == rClose) exitApp();
            else if (r == rArt) pickFilter();
            else if (r == rShuf) pl.setShuffle(!pl.shuffle);
            else if (r == rRep) pl.cycleRepeat();
            else if (r == rPrev) prev();
            else if (r == rNext) next(true);
            else if (r == rPlay) toggle();
            else if (r == rStop) stop();
            else if (r == rStart) mainMenu();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mode = 0; moved = false; lpFired = false; downX = x; downY = y; lastY = y; pressed = null; fl.down(e);
                    if (hit(rSeek, x, y, d(12))) { mode = 2; seekF = Math.max(0, Math.min(1, (x - rSeek.left) / rSeek.width())); }
                    else if (hit(rVol, x, y, d(12))) { mode = 3; setVol((x - rVol.left) / rVol.width()); }
                    else if (rList.contains(x, y)) { mode = 1; postDelayed(lp, ViewConfiguration.getLongPressTimeout()); }
                    else {
                        RectF[] bs = {rClose, rArt, rShuf, rRep, rPrev, rPlay, rStop, rNext, rStart};
                        for (RectF r : bs) if (r.contains(x, y)) { pressed = r; mode = 4; }
                    }
                    invalidate(); return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mode == 2) seekF = Math.max(0, Math.min(1, (x - rSeek.left) / rSeek.width()));
                    else if (mode == 3) setVol((x - rVol.left) / rVol.width());
                    else if (mode == 1) {
                        if (Math.abs(y - downY) > d(8)) { moved = true; removeCallbacks(lp); }
                        if (moved) off -= y - lastY;
                        lastY = y;
                    }
                    else if (mode == 4 && pressed != null && !pressed.contains(x, y)) pressed = null;
                    fl.move(e); invalidate(); return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    removeCallbacks(lp);
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    fl.up(e, up && mode == 1 && moved, this);
                    if (up && mode == 2 && pl.loaded() && dur() > 0) seekTo((int) (seekF * dur()));
                    seekF = -1;
                    if (up && mode == 4 && pressed != null) press(pressed);
                    else if (up && mode == 1 && !moved && !lpFired) { int i = rowAt(y); if (i >= 0) playList(songs(), i); }
                    pressed = null; mode = 0; invalidate(); return true;
                }
            }
            return true;
        }
    }


    // =====================================================================
    //  Retro themes (one palette-driven skin): Windows 95, Game Boy, Winamp, Cassette
    // =====================================================================
    // bg, fg, accent, dim, header bg, header text, selected-row bg, selected-row text   (index = skin - 2)
    static final int[][] RP = {
        {0xFFC0C0C0, 0xFF000000, 0xFF000080, 0xFF808080, 0xFF000080, 0xFFFFFFFF, 0xFF000080, 0xFFFFFFFF},  // 2 Windows 95
        {0xFF9BBC0F, 0xFF0F380F, 0xFF306230, 0xFF8BAC0F, 0xFF0F380F, 0xFF9BBC0F, 0xFF0F380F, 0xFF9BBC0F},  // 3 Game Boy
        {0xFF1A1A22, 0xFF00E000, 0xFFFFD84A, 0xFF2A3A2A, 0xFF3A3A4A, 0xFFD0D0D0, 0xFF0000C6, 0xFFFFFFFF},  // 4 Winamp
        {0xFFD8C9A3, 0xFF2B2118, 0xFFC8460F, 0xFFC2B183, 0xFFC8460F, 0xFFFFF3D6, 0xFFC8460F, 0xFFFFF3D6}}; // 5 Cassette

    class Retro extends View implements Scrolly {
        final int bg, fg, ac, dim, hb, ht, hl, hlt; final boolean w95, gb;
        final Fling fl = new Fling();
        public void top() { off = 0; fl.stop(); }
        final TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF tmp = new RectF(), rTitle = new RectF(), rTheme = new RectF(), rSeek = new RectF(), rList = new RectF(), rArtist = new RectF();
        final RectF[] rb = new RectF[5];
        final float[] lv = new float[16];
        float off, rowH, listT, downX, downY, lastY, seekF = -1; int mode, pressed = -1, taps; long lastTap; boolean moved, lpFired;
        Bitmap gbSrc, gbOut;

        Retro(Context c, int th) {
            super(c);
            int[] r = RP[th - 2];
            bg = r[0]; fg = r[1]; ac = r[2]; dim = r[3]; hb = r[4]; ht = r[5]; hl = r[6]; hlt = r[7];
            w95 = th == 2; gb = th == 3;
            for (int i = 0; i < 5; i++) rb[i] = new RectF();
        }

        ArrayList<Song> songs() { return filtered(); }

        void box(Canvas c, RectF r, int fill, boolean sunk, int border) {
            p.setShader(null); p.setStyle(Paint.Style.FILL);
            p.setColor(fill); c.drawRect(r, p);
            if (w95) {
                float b = d(2);
                p.setColor(sunk ? 0xFF404040 : WHITE); c.drawRect(r.left, r.top, r.right, r.top + b, p); c.drawRect(r.left, r.top, r.left + b, r.bottom, p);
                p.setColor(sunk ? WHITE : 0xFF404040); c.drawRect(r.left, r.bottom - b, r.right, r.bottom, p); c.drawRect(r.right - b, r.top, r.right, r.bottom, p);
            } else {
                float sw = d(2);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw); p.setColor(border);
                c.drawRect(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2, p); p.setStyle(Paint.Style.FILL);
            }
        }

        /** Game Boy: the cover in the four green shades. */
        Bitmap gbArt(Bitmap src) {
            if (src == gbSrc && gbOut != null) return gbOut;
            int n = 48; Bitmap sm = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src, n * 2, n * 2, true), n, n, true);
            int[] px = new int[n * n], pal = {0xFF0F380F, 0xFF306230, 0xFF8BAC0F, 0xFF9BBC0F}; sm.getPixels(px, 0, n, 0, 0, n, n);
            for (int i = 0; i < px.length; i++) {
                int l = lum(px[i]), x = i % n, y = i / n, bayer = ((x & 1) * 2 + (y & 1) * 3) % 4 * 8 - 12;   // tiny ordered dither
                px[i] = pal[Math.max(0, Math.min(3, (l + bayer) * 4 / 256))];
            }
            Bitmap o = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); o.setPixels(px, 0, n, 0, 0, n, n);
            gbSrc = src; gbOut = o; return o;
        }

        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), m = d(10), hh = d(42);
            p.reset(); p.setAntiAlias(true);
            c.drawColor(bg);
            ArrayList<Song> l = songs(); Song np = now(); boolean on = playing();
            int pf = w95 ? BLACK : bg, pc = w95 ? 0xFF00E000 : fg, pa = w95 ? 0xFFFFFF00 : ac, edge = fg;

            // ----- header -----
            p.setColor(hb); c.drawRect(0, 0, W, hh, p);
            if (hb == bg) { p.setColor(fg); c.drawRect(0, hh - d(2), W, hh, p); }
            rTitle.set(0, 0, W - d(110), hh);
            txt(c, p, "TAPE DECK", m, hh / 2 + d(6), d(18), ht, true, Paint.Align.LEFT, W - d(130));
            rTheme.set(W - d(100), d(6), W - m, hh - d(6));
            box(c, rTheme, w95 ? 0xFFC0C0C0 : hb, mode == 5, edge);
            txt(c, p, "MENU", rTheme.centerX(), rTheme.centerY() + d(4.5f), d(12), w95 ? BLACK : ht, true, Paint.Align.CENTER, 0);

            // ----- now playing panel -----
            float pt = hh + d(10), pb = pt + d(128), x = m + d(12), mw = W - 2 * m - d(24);
            tmp.set(m, pt, W - m, pb); box(c, tmp, pf, true, edge);
            Bitmap ab = artFor(np); float tw = ab != null ? mw - d(72) : mw;
            if (np == null) txt(c, p, "NO TAPE LOADED", x, pt + d(26), d(16), pc, true, Paint.Align.LEFT, mw);
            else {
                txt(c, p, np.title, x, pt + d(26), d(18), pc, true, Paint.Align.LEFT, tw);
                txt(c, p, np.artist, x, pt + d(44), d(14), pa, false, Paint.Align.LEFT, tw);
                txt(c, p, np.album, x, pt + d(60), d(12), pc, false, Paint.Align.LEFT, tw);
            }
            if (ab != null) {
                Bitmap shown = gb ? gbArt(ab) : ab; float as = d(60), ax = x + mw - as, ay = pt + d(10);
                tmp.set(ax, ay, ax + as, ay + as);
                p.setFilterBitmap(!gb); c.drawBitmap(shown, new Rect(0, 0, shown.getWidth(), shown.getHeight()), tmp, p); p.setFilterBitmap(false);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(2)); p.setColor(pc); c.drawRect(tmp, p); p.setStyle(Paint.Style.FILL);
            }
            float vb = pt + d(100), cw = mw / lv.length;
            p.setShader(null); p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < lv.length; i++) {
                float tg = on ? (float) Math.pow(rnd.nextFloat(), 1.4) * (1f - i * .02f) : 0; lv[i] = lv[i] * .55f + tg * .45f;
                int lit = Math.round(lv[i] * 6);
                for (int r = 0; r < 6; r++) {
                    p.setColor(r < lit ? (r >= 4 ? pa : pc) : dim);
                    float bx = x + i * cw, by = vb - r * d(5);
                    c.drawRect(bx + d(1.5f), by - d(4), bx + cw - d(1.5f), by, p);
                }
            }
            rSeek.set(x, pb - d(26), x + mw, pb - d(16));
            int du = dur(), ps = pos(), ns = 30; float f = seekF >= 0 ? seekF : (du > 0 ? Math.min(1f, ps / (float) du) : 0), sg = mw / ns;
            if (seekF >= 0) ps = (int) (seekF * du);
            for (int i = 0; i < ns; i++) { p.setColor(i < Math.round(f * ns) ? pc : dim); c.drawRect(x + i * sg + d(1), rSeek.top, x + (i + 1) * sg - d(1), rSeek.bottom, p); }
            txt(c, p, fmt(ps), x, pb - d(5), d(11), pc, false, Paint.Align.LEFT, 0);
            txt(c, p, fmt(du), x + mw, pb - d(5), d(11), pc, false, Paint.Align.RIGHT, 0);

            // ----- controls -----
            float cy0 = pb + d(10), ch = d(42), bw = (W - 2 * m - 4 * d(6)) / 5;
            String[] lb = {"PREV", on ? "PAUSE" : "PLAY", "NEXT", "SHUF", new String[]{"REPT", "R:ALL", "R:ONE"}[pl.repeat]};
            for (int i = 0; i < 5; i++) {
                rb[i].set(m + i * (bw + d(6)), cy0, m + i * (bw + d(6)) + bw, cy0 + ch);
                boolean tog = (i == 3 && pl.shuffle) || (i == 4 && pl.repeat != 0), prs = pressed == i;
                box(c, rb[i], (tog || prs) ? hl : (w95 ? 0xFFC0C0C0 : bg), w95 && prs, fg);
                txt(c, p, lb[i], rb[i].centerX(), rb[i].centerY() + d(4.5f), d(12), (tog || prs) ? hlt : (w95 ? BLACK : fg), true, Paint.Align.CENTER, 0);
            }

            // ----- artist filter -----
            float lt = cy0 + ch + d(10);
            rArtist.set(m, lt, W - m, lt + d(32));
            boolean ap = mode == 7; int atc = ap ? hlt : (w95 ? BLACK : fg);
            box(c, rArtist, ap ? hl : (w95 ? 0xFFC0C0C0 : bg), w95 && ap, fg);
            txt(c, p, filterLabel().toUpperCase(Locale.US), rArtist.left + d(12), rArtist.centerY() + d(4.5f), d(12), atc, true, Paint.Align.LEFT, rArtist.width() - d(48));
            p.setColor(atc); p.setStyle(Paint.Style.FILL);
            Path dn = new Path(); dn.moveTo(rArtist.right - d(24), rArtist.centerY() - d(3)); dn.lineTo(rArtist.right - d(12), rArtist.centerY() - d(3)); dn.lineTo(rArtist.right - d(18), rArtist.centerY() + d(4)); dn.close(); c.drawPath(dn, p);

            // ----- song list -----
            float lt2 = rArtist.bottom + d(8), lbm = H - m;
            rList.set(m, lt2, W - m, lbm); box(c, rList, w95 ? WHITE : bg, true, edge);
            listT = lt2 + d(4); rowH = d(32);
            float viewH = lbm - d(4) - listT, maxOff = Math.max(0, l.size() * rowH - viewH);
            off = fl.apply(off, maxOff, this);
            c.save(); c.clipRect(rList.left + d(3), listT, rList.right - d(3), lbm - d(4));
            if (l.isEmpty()) txt(c, p, "NO MUSIC FOUND", rList.centerX(), listT + d(40), d(14), w95 ? BLACK : fg, true, Paint.Align.CENTER, 0);
            else for (int i = Math.max(0, (int) (off / rowH)); i < l.size(); i++) {
                float ry = listT + i * rowH - off; if (ry > lbm) break;
                Song s = l.get(i); boolean sel = s.equals(np);
                p.setShader(null); p.setStyle(Paint.Style.FILL);
                if (sel) { p.setColor(hl); c.drawRect(rList.left + d(3), ry, rList.right - d(3), ry + rowH, p); }
                else { p.setColor(dim); c.drawRect(rList.left + d(6), ry + rowH - 1, rList.right - d(6), ry + rowH, p); }
                int tc = sel ? hlt : (w95 ? BLACK : fg), ac2 = sel ? hlt : (w95 ? 0xFF404040 : ac);
                txt(c, p, s.title, rList.left + d(12), ry + rowH * .64f, d(14), tc, sel, Paint.Align.LEFT, rList.width() * .6f - d(34));
                txt(c, p, s.artist, rList.right - d(12), ry + rowH * .64f, d(12), ac2, false, Paint.Align.RIGHT, rList.width() * .3f);
            }
            c.restore();
            if (maxOff > 0) {
                float tk = Math.max(d(24), viewH * viewH / (l.size() * rowH)), ty = listT + (viewH - tk) * off / maxOff;
                p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor((fg & 0xFFFFFF) | 0x88000000); c.drawRect(rList.right - d(7), ty, rList.right - d(4), ty + tk, p);
            }
        }

        int rowAt(float y) {
            int i = (int) ((y - listT + off) / rowH);
            return y >= listT && i >= 0 && i < songs().size() ? i : -1;
        }

        final Runnable lp = new Runnable() { public void run() {
            if (mode != 1 || moved) return;
            int i = rowAt(downY); if (i < 0) return;
            lpFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            songMenu(new ArrayList<>(songs()), i);
        }};

        void act(int i) {
            if (i == 0) prev(); else if (i == 1) toggle(); else if (i == 2) next(true);
            else if (i == 3) pl.setShuffle(!pl.shuffle); else pl.cycleRepeat();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mode = 0; moved = false; lpFired = false; downX = x; downY = y; lastY = y; pressed = -1; fl.down(e);
                    if (rTheme.contains(x, y)) mode = 5;
                    else if (x >= rSeek.left - d(14) && x <= rSeek.right + d(14) && y >= rSeek.top - d(14) && y <= rSeek.bottom + d(14)) { mode = 2; seekF = Math.max(0, Math.min(1, (x - rSeek.left) / rSeek.width())); }
                    else {
                        for (int i = 0; i < 5; i++) if (rb[i].contains(x, y)) { pressed = i; mode = 4; }
                        if (mode == 0 && rArtist.contains(x, y)) mode = 7;
                        if (mode == 0 && rList.contains(x, y)) { mode = 1; postDelayed(lp, ViewConfiguration.getLongPressTimeout()); }
                    }
                    invalidate(); return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mode == 2) seekF = Math.max(0, Math.min(1, (x - rSeek.left) / rSeek.width()));
                    else if (mode == 1) {
                        if (Math.abs(y - downY) > d(8)) { moved = true; removeCallbacks(lp); }
                        if (moved) off -= y - lastY;
                        lastY = y;
                    }
                    else if (mode == 4 && pressed >= 0 && !rb[pressed].contains(x, y)) pressed = -1;
                    else if (mode == 5 && !rTheme.contains(x, y)) mode = 0;
                    else if (mode == 7 && !rArtist.contains(x, y)) mode = 0;
                    fl.move(e); invalidate(); return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    removeCallbacks(lp);
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    fl.up(e, up && mode == 1 && moved, this);
                    if (up && mode == 2 && pl.loaded() && dur() > 0) seekTo((int) (seekF * dur()));
                    seekF = -1;
                    if (up && mode == 5) mainMenu();
                    else if (up && mode == 7) pickFilter();
                    else if (up && mode == 4 && pressed >= 0) act(pressed);
                    else if (up && mode == 1 && !moved && !lpFired) { int i = rowAt(y); if (i >= 0) playList(songs(), i); }
                    pressed = -1; mode = 0; invalidate(); return true;
                }
            }
            return true;
        }
    }

    // =====================================================================
    //  * ???   (the secret Undertale theme)
    //  Portrait + dialogue box, HP-bar seek, bullet-board visualizer with a draggable SOUL,
    //  real album art (pixel-sprite mode or HD), a full-screen "poster" view, text blips.
    // =====================================================================
    class Ut extends View implements Scrolly {
        static final int OR = 0xFFFF7A00, YE = 0xFFFFFF00, RD = 0xFFFF0000, GR = 0xFFBBBBBB, DG = 0xFF777777;
        static final float S = 7f, M = 9f, L = 12f;   // text tiers (character width in dp, snapped to the pixel grid for Press Start 2P)
        final TextPaint p = new TextPaint();             // no anti-aliasing: crisp pixels
        final RectF tmp = new RectF(), rTitle = new RectF(), rTheme = new RectF(), rFont = new RectF(), rPort = new RectF(), rDlg = new RectF(),
            rHp = new RectF(), rBoard = new RectF(), rArtist = new RectF(), rList = new RectF(), rBack = new RectF();
        final RectF[] rb = new RectF[5];
        final float[] bx = new float[28], by = new float[28], bv = new float[28]; final int[] bk = new int[28];
        final String[] FLAV = {"* It's a beautiful day outside.", "* Smells like cinnamon and butterscotch.", "* You hear distant music...", "* Determination.", "* The tape deck hums quietly.", "* Birds are singing, flowers are blooming..."};
        class UL { String s; float cw; int col; UL(String s, float cw, int col) { this.s = s; this.cw = cw; this.col = col; } }

        boolean poster, pix = true, moved, lpFired; int mode, pressed = -1, shown; String ovr, typedKey = "", flavor;
        float sx = -1, sy, off, rowH, listT, downX, downY, lastY, seekF = -1; long last, typedAt, ovrUntil, touchedAt, hitUntil;
        AudioTrack sTxt, sSel;

        final Fling fl = new Fling();
        AudioTrack sHit, sSlash;
        public void top() { off = 0; fl.stop(); }

        Ut(Context c) {
            super(c);
            sHit = tone(180, 140, .35f); sSlash = tone(520, 90, .30f);
            for (int i = 0; i < 5; i++) rb[i] = new RectF();
            pix = getPreferences(0).getBoolean("utpix", true);
            flavor = FLAV[rnd.nextInt(FLAV.length)];
            sTxt = tone(640, 55, .22f); sSel = tone(930, 70, .30f);
            last = SystemClock.uptimeMillis();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            for (AudioTrack t : new AudioTrack[]{sTxt, sSel, sHit, sSlash}) try { if (t != null) t.release(); } catch (Throwable e) { }
            sTxt = null; sSel = null; sHit = null; sSlash = null;
        }

        // ----- sound -----
        AudioTrack tone(int hz, int ms, float vol) {
            try {
                int sr = 22050, n = sr * ms / 1000; byte[] b = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    double ph = (i * (double) hz / sr) % 1.0;
                    short v = (short) ((ph < .5 ? 1 : -1) * 5200 * (1 - i / (double) n * .55));
                    b[2 * i] = (byte) (v & 255); b[2 * i + 1] = (byte) ((v >> 8) & 255);
                }
                AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, b.length, AudioTrack.MODE_STATIC);
                t.write(b, 0, b.length); t.setVolume(vol); return t;
            } catch (Throwable e) { return null; }
        }

        void beep(AudioTrack t) { if (t == null) return; try { t.stop(); t.reloadStaticData(); t.play(); } catch (Throwable e) { } }

        void say(String s) { ovr = s; ovrUntil = SystemClock.uptimeMillis() + 2600; }

        boolean back() {
            if (fight != null) { fight = null; say("* You fled."); invalidate(); return true; }
            if (!poster) return false; poster = false; invalidate(); return true;
        }

        // ----- text -----
        float ts(float cw) { float px = d(cw) / utAdv; if (utGrid > 0) px = Math.max(utGrid, Math.round(px / utGrid) * utGrid); return px; }
        float cap(float cw) { return ts(cw) * .75f; }
        float tw(String s, float cw) { p.setTypeface(utFont); p.setTextSize(ts(cw)); return p.measureText(s); }

        void tx(Canvas c, String s, float x, float cy, float cw, int col, Paint.Align al, float maxW) {
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setTypeface(utFont); p.setTextSize(ts(cw)); p.setColor(col); p.setTextAlign(al);
            if (maxW > 0) s = TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString();
            c.drawText(s, x, cy + cap(cw) / 2, p);   // cy = vertical centre of the capital letters
        }

        // Two-layer Undertale-logo lettering: Monster Friend Back (orange) under Monster Friend Fore (white). Font grid = em/10, so snap to 10px.
        float logoPx(float wantDp) { return Math.max(10f, Math.round(d(wantDp) / 10f) * 10f); }

        boolean logoOk(String s, float wantDp, float maxW) {
            if (logoFore == null || logoBack == null || !s.matches("[A-Z0-9 .,'!?&-]+")) return false;
            p.setTypeface(logoFore); p.setTextSize(logoPx(wantDp)); return p.measureText(s) <= maxW;
        }

        void logo(Canvas c, String s, float x, float cy, float wantDp, Paint.Align al, float maxW, int backCol, int foreCol) {
            if (logoFore == null || logoBack == null) { tx(c, s, x, cy, M, foreCol, al, maxW); return; }
            float px = logoPx(wantDp);
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setTextAlign(al); p.setTypeface(logoFore); p.setTextSize(px);
            while (px > 10 && p.measureText(s) > maxW) { px -= 10; p.setTextSize(px); }
            float by = cy + px * .35f;
            p.setTypeface(logoBack); p.setColor(backCol); c.drawText(s, x, by, p);
            p.setTypeface(logoFore); p.setColor(foreCol); c.drawText(s, x, by, p);
        }

        ArrayList<UL> wrap(String s, float cw, float maxW, int maxLines, int col) {
            ArrayList<UL> out = new ArrayList<>(); String cur = "";
            for (String w : s.split(" ")) {
                String t = cur.isEmpty() ? w : cur + " " + w;
                if (cur.isEmpty() || tw(t, cw) <= maxW) cur = t; else { out.add(new UL(cur, cw, col)); cur = "  " + w; }
            }
            if (!cur.isEmpty()) out.add(new UL(cur, cw, col));
            while (out.size() > maxLines) out.remove(out.size() - 1);
            return out;
        }

        // ----- pixel art -----
        void heart(Canvas c, float x, float y, float s, int col) {
            String[] g = {".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."};
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(col);
            float x0 = x - 3.5f * s, y0 = y - 3 * s;
            for (int j = 0; j < g.length; j++) for (int i = 0; i < 7; i++) if (g.length > j && g[j].charAt(i) == 'X')
                c.drawRect(Math.round(x0 + i * s), Math.round(y0 + j * s), Math.round(x0 + (i + 1) * s), Math.round(y0 + (j + 1) * s), p);
        }

        void note(Canvas c, float cx, float cy, float s, int col) {
            String[] g = {"..XXXXXX", "..XXXXXX", "..XX..XX", "..XX..XX", "..XX..XX", "XXXX.XXX", "XXXX.XXX", "XXXX.XXX"};
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(col);
            float x0 = cx - 4 * s, y0 = cy - 4 * s;
            for (int j = 0; j < 8; j++) for (int i = 0; i < 8; i++) if (g[j].charAt(i) == 'X')
                c.drawRect(Math.round(x0 + i * s), Math.round(y0 + j * s), Math.round(x0 + (i + 1) * s), Math.round(y0 + (j + 1) * s), p);
        }

        void star(Canvas c, float x, float y, float r, int col) {   // the SAVE point sparkle
            Path ph = new Path(); float k = r * .24f;
            ph.moveTo(x, y - r); ph.lineTo(x + k, y - k); ph.lineTo(x + r, y); ph.lineTo(x + k, y + k);
            ph.lineTo(x, y + r); ph.lineTo(x - k, y + k); ph.lineTo(x - r, y); ph.lineTo(x - k, y - k); ph.close();
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(col); c.drawPath(ph, p);
        }

        void ubox(Canvas c, RectF r, int col, float t) {
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(col); c.drawRect(r, p);
            p.setColor(BLACK); c.drawRect(r.left + t, r.top + t, r.right - t, r.bottom - t, p);
        }

        void cover(Canvas c, RectF inner, Song s, int n, boolean sprite) {
            Bitmap a = s == null ? null : (sprite ? pixArt(s, n) : artFor(s));
            if (a != null) {
                p.setShader(null); p.setFilterBitmap(!sprite);
                c.drawBitmap(a, new Rect(0, 0, a.getWidth(), a.getHeight()), inner, p); p.setFilterBitmap(false);
            } else note(c, inner.centerX(), inner.centerY(), Math.max(d(1.2f), inner.width() / 18f), 0xFF555555);
        }

        // ----- sections -----
        void header(Canvas c, float W, float hh, float m, boolean on, long t0) {
            rTitle.set(0, 0, W - d(160), hh);
            star(c, m + d(9), hh / 2, d(7) + (on ? (float) Math.sin(t0 / 140.0) * d(1.5f) : 0), YE);
            logo(c, "TAPE DECK", m + d(26), hh / 2, 19, Paint.Align.LEFT, rTitle.right - m - d(26), OR, WHITE);
            rTheme.set(W - m - d(76), d(6), W - m, hh - d(6));
            rFont.set(rTheme.left - d(6) - d(62), d(6), rTheme.left - d(6), hh - d(6));
            int fc = mode == 9 ? YE : OR, tc = mode == 5 ? YE : WHITE;
            ubox(c, rFont, fc, d(2.5f)); tx(c, "FIGHT", rFont.centerX(), rFont.centerY(), S, fc, Paint.Align.CENTER, 0);
            ubox(c, rTheme, tc, d(2.5f)); tx(c, "MENU", rTheme.centerX(), rTheme.centerY(), S, tc, Paint.Align.CENTER, 0);
        }

        void dialogue(Canvas c, RectF r, Song np, long t0) {
            ubox(c, r, WHITE, d(3));
            float pad = d(10), maxW = r.width() - 2 * pad; ArrayList<UL> ls = new ArrayList<>();
            if (ovr != null && t0 < ovrUntil) ls.addAll(wrap(ovr, M, maxW, 3, WHITE));
            else if (all.isEmpty()) { ls.add(new UL("* But nobody came.", M, WHITE)); ls.add(new UL("  (No music found.)", S, GR)); }
            else if (np == null) { ls.addAll(wrap(flavor, M, maxW, 3, WHITE)); ls.add(new UL("  (Pick a song.)", S, GR)); }
            else { ls.addAll(wrap("* " + np.title, M, maxW, 2, WHITE)); ls.add(new UL("  " + np.artist, S, 0xFFDDDDDD)); ls.add(new UL("  " + np.album, S, DG)); }
            StringBuilder key = new StringBuilder(); int total = 0;
            for (UL u : ls) { key.append(u.s).append('\n'); total += u.s.length(); }
            if (!key.toString().equals(typedKey)) { typedKey = key.toString(); typedAt = t0; shown = 0; }
            int k = (int) ((t0 - typedAt) / 26), rem = k; float y = r.top + pad;
            if (k > shown && shown < total) { beep(sTxt); }
            shown = Math.max(shown, k);
            c.save(); c.clipRect(r.left + d(3), r.top + d(3), r.right - d(3), r.bottom - d(3));
            for (UL u : ls) {
                float lh = ts(u.cw) * 1.5f; y += lh * .5f;
                String part = u.s.substring(0, Math.max(0, Math.min(u.s.length(), rem))); rem -= u.s.length();
                if (!part.isEmpty()) tx(c, part, r.left + pad, y, u.cw, u.col, Paint.Align.LEFT, maxW);
                y += lh * .5f + d(2);
            }
            c.restore();
        }

        float stat(Canvas c, float y, float W, float m, Song np) {
            float rh = d(20), cy = y + rh / 2; String left = "TAPE  LV " + Math.min(99, all.size());
            tx(c, left, m, cy, M, WHITE, Paint.Align.LEFT, 0);
            float hx = m + tw(left, M) + d(10); tx(c, "HP", hx, cy, S, WHITE, Paint.Align.LEFT, 0);
            float x0 = hx + tw("HP", S) + d(6), x1 = W - m - tw("00:00/00:00", S) - d(8);
            rHp.set(x0, y + d(1), x1, y + rh - d(1));
            int du = dur(), ps = pos(); float f = seekF >= 0 ? seekF : (du > 0 ? Math.min(1f, ps / (float) du) : 0);
            if (seekF >= 0) ps = (int) (seekF * du);
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(RD); c.drawRect(rHp, p);
            p.setColor(YE); c.drawRect(rHp.left, rHp.top, rHp.left + rHp.width() * f, rHp.bottom, p);
            tx(c, fmt(ps) + "/" + fmt(du), W - m, cy, S, WHITE, Paint.Align.RIGHT, 0);
            return y + rh;
        }

        void board(Canvas c, boolean on, long t0, float dt) {
            ubox(c, rBoard, WHITE, d(3));
            float bl = rBoard.left + d(3), br = rBoard.right - d(3), bt = rBoard.top + d(3), bb = rBoard.bottom - d(3);
            if (sx < 0) { sx = rBoard.centerX(); sy = bb - d(16); }
            boolean human = t0 - touchedAt < 3500;
            if (on && rnd.nextFloat() < dt * 8f) for (int i = 0; i < bx.length; i++) if (bv[i] == 0) {
                bx[i] = bl + d(8) + rnd.nextFloat() * (br - bl - d(16)); by[i] = bt - d(14); bv[i] = d(70) + rnd.nextFloat() * d(110); bk[i] = rnd.nextInt(4) == 0 ? 2 : (rnd.nextInt(3) == 0 ? 1 : 0); break;
            }
            if (!human) {
                float tx0 = rBoard.centerX() + (float) Math.sin(t0 / 1100.0) * (br - bl) * .35f, ty0 = bb - d(16) + (float) Math.sin(t0 / 430.0) * d(3), k = Math.min(1f, dt * 3f);
                sx += (tx0 - sx) * k; sy += (ty0 - sy) * k;
            }
            sx = Math.max(bl + d(8), Math.min(br - d(8), sx)); sy = Math.max(bt + d(8), Math.min(bb - d(8), sy));
            c.save(); c.clipRect(bl, bt, br, bb); p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(WHITE);
            for (int i = 0; i < bx.length; i++) if (bv[i] != 0) {
                by[i] += bv[i] * dt;
                float hh = bk[i] == 0 ? d(3.5f) : (bk[i] == 1 ? d(8) : d(14));
                if (human && Math.abs(bx[i] - sx) < d(7) + (bk[i] == 0 ? 0 : d(2)) && Math.abs(by[i] - sy) < d(7) + hh) { hitUntil = t0 + 200; bv[i] = 0; continue; }
                if (by[i] > bb + d(20)) { bv[i] = 0; continue; }
                p.setColor(WHITE);
                if (bk[i] == 0) c.drawCircle(bx[i], by[i], d(3.5f), p);
                else {
                    c.drawRect(bx[i] - d(2), by[i] - hh, bx[i] + d(2), by[i] + hh, p);
                    c.drawCircle(bx[i] - d(2.4f), by[i] - hh, d(2.8f), p); c.drawCircle(bx[i] + d(2.4f), by[i] - hh, d(2.8f), p);
                    c.drawCircle(bx[i] - d(2.4f), by[i] + hh, d(2.8f), p); c.drawCircle(bx[i] + d(2.4f), by[i] + hh, d(2.8f), p);
                }
            }
            c.restore();
            heart(c, sx, sy, d(1.9f), t0 < hitUntil ? WHITE : RD);
        }

        void buttons(Canvas c, float y, float W, float m, boolean on) {
            float ch = d(40), bw = (W - 2 * m - 4 * d(6)) / 5;
            String[] lb = {"PREV", on ? "PAUSE" : "PLAY", "NEXT", "SHUF", new String[]{"LOOP", "L:ALL", "L:ONE"}[pl.repeat]};
            for (int i = 0; i < 5; i++) {
                rb[i].set(m + i * (bw + d(6)), y, m + i * (bw + d(6)) + bw, y + ch);
                boolean sel = pressed == i || (i == 3 && pl.shuffle) || (i == 4 && pl.repeat != 0); int col = sel ? YE : OR;
                ubox(c, rb[i], col, d(3));
                float tw = tw(lb[i], M), hw = sel ? d(11) : 0, x = rb[i].centerX() - (tw + hw) / 2;
                if (sel) heart(c, x + d(4), rb[i].centerY(), d(1.1f), RD);
                tx(c, lb[i], x + hw, rb[i].centerY(), M, col, Paint.Align.LEFT, 0);
            }
        }

        void artistBar(Canvas c, float y, float W, float m) {
            rArtist.set(m, y, W - m, y + d(32)); boolean on = mode == 7;
            ubox(c, rArtist, on ? YE : WHITE, d(3));
            float x = rArtist.left + d(12);
            if (on) { heart(c, x + d(4), rArtist.centerY(), d(1.1f), RD); x += d(14); }
            tx(c, "* " + filterLabel(), x, rArtist.centerY(), S, on ? YE : WHITE, Paint.Align.LEFT, rArtist.right - x - d(34));
            Path dn = new Path(); float ax = rArtist.right - d(20), ay = rArtist.centerY();
            dn.moveTo(ax - d(6), ay - d(3)); dn.lineTo(ax + d(6), ay - d(3)); dn.lineTo(ax, ay + d(4)); dn.close();
            p.setStyle(Paint.Style.FILL); p.setColor(on ? YE : WHITE); c.drawPath(dn, p);
        }

        void list(Canvas c, ArrayList<Song> l, Song np) {
            ubox(c, rList, WHITE, d(3));
            listT = rList.top + d(5); rowH = d(46);
            float bm = rList.bottom - d(5), viewH = bm - listT, maxOff = Math.max(0, l.size() * rowH - viewH);
            off = fl.apply(off, maxOff, this);
            c.save(); c.clipRect(rList.left + d(3), listT, rList.right - d(3), bm);
            if (l.isEmpty()) tx(c, "* ...", rList.centerX(), listT + d(34), M, WHITE, Paint.Align.CENTER, 0);
            else for (int i = Math.max(0, (int) (off / rowH)); i < l.size(); i++) {
                float ry = listT + i * rowH - off; if (ry > bm) break;
                Song s = l.get(i); boolean sel = s.equals(np);
                float th = d(36), tx0 = rList.left + d(28), ty0 = ry + (rowH - th) / 2, x = tx0 + th + d(10);
                if (sel) heart(c, rList.left + d(15), ry + rowH / 2, d(1.3f), RD);
                tmp.set(tx0, ty0, tx0 + th, ty0 + th);
                p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(sel ? YE : 0xFF555555); c.drawRect(tmp.left - d(1.5f), tmp.top - d(1.5f), tmp.right + d(1.5f), tmp.bottom + d(1.5f), p);
                p.setColor(BLACK); c.drawRect(tmp, p);
                cover(c, tmp, s, 16, true);
                float mw = rList.right - x - d(12);
                tx(c, s.title, x, ry + rowH * .35f, M, sel ? YE : WHITE, Paint.Align.LEFT, mw);
                tx(c, s.artist, x, ry + rowH * .72f, S, GR, Paint.Align.LEFT, mw);
            }
            c.restore();
            if (maxOff > 0) {
                float tk = Math.max(d(24), viewH * viewH / (l.size() * rowH)), ty = listT + (viewH - tk) * off / maxOff;
                p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(WHITE); c.drawRect(rList.right - d(7), ty, rList.right - d(4), ty + tk, p);
            }
        }

        void posterView(Canvas c, float W, float H, float m, float hh, Song np, boolean on, long t0) {
            float sz = Math.min(W - 2 * m, H * .42f);
            rPort.set((W - sz) / 2, hh + d(8), (W + sz) / 2, hh + d(8) + sz);
            ubox(c, rPort, WHITE, d(4)); tmp.set(rPort.left + d(4), rPort.top + d(4), rPort.right - d(4), rPort.bottom - d(4));
            cover(c, tmp, np, 48, pix);
            float y = rPort.bottom + d(14), cx = W / 2, maxW = W - 2 * m;
            String up = np.title.toUpperCase(Locale.US);
            if (logoOk(up, 17, maxW)) { logo(c, up, cx, y + d(14), 17, Paint.Align.CENTER, maxW, OR, WHITE); y += d(32); }
            else for (UL u : wrap(np.title, L, maxW, 2, WHITE)) { float lh = ts(L) * 1.5f; tx(c, u.s.trim(), cx, y + lh / 2, L, WHITE, Paint.Align.CENTER, maxW); y += lh; }
            tx(c, np.artist, cx, y + d(8), M, 0xFFDDDDDD, Paint.Align.CENTER, maxW); y += d(22);
            tx(c, np.album, cx, y + d(4), S, DG, Paint.Align.CENTER, maxW); y += d(22);
            y = stat(c, y, W, m, np) + d(14);
            buttons(c, y, W, m, on); y += d(40) + d(14);
            rBack.set(m, y, W - m, y + d(34)); boolean bp = mode == 12;
            ubox(c, rBack, bp ? YE : WHITE, d(3));
            tx(c, pix ? "* BACK   (TAP ART: HD)" : "* BACK   (TAP ART: SPRITE)", rBack.centerX(), rBack.centerY(), S, bp ? YE : WHITE, Paint.Align.CENTER, rBack.width() - d(16));
        }

        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), m = d(12), hh = d(40);
            p.reset(); c.drawColor(BLACK);
            long t0 = SystemClock.uptimeMillis(); float dt = Math.min(50, t0 - last) / 1000f; last = t0;
            ArrayList<Song> l = songs(); Song np = now(); boolean on = playing();
            if (fight != null) { fight.draw(c, W, H, t0, on ? dt : 0); return; }
            rPort.setEmpty(); rDlg.setEmpty(); rBoard.setEmpty(); rArtist.setEmpty(); rList.setEmpty(); rBack.setEmpty(); rHp.setEmpty();
            for (int i = 0; i < 5; i++) rb[i].setEmpty();
            if (poster && np == null) poster = false;
            if (poster) {
                Bitmap bd = pixArt(np, 8);
                if (bd != null) {   // backdrop: the cover, melted into a handful of giant pixels and dimmed
                    p.setFilterBitmap(true); c.drawBitmap(bd, new Rect(0, 0, bd.getWidth(), bd.getHeight()), new RectF(0, 0, W, H), p); p.setFilterBitmap(false);
                    p.setStyle(Paint.Style.FILL); p.setColor(0xD0000000); c.drawRect(0, 0, W, H, p);
                }
                header(c, W, hh, m, on, t0); posterView(c, W, H, m, hh, np, on, t0); return;
            }
            header(c, W, hh, m, on, t0);
            float D = d(112), top = hh + d(6);
            rPort.set(m, top, m + D, top + D); ubox(c, rPort, WHITE, d(3));
            tmp.set(rPort.left + d(3), rPort.top + d(3), rPort.right - d(3), rPort.bottom - d(3)); cover(c, tmp, np, 40, pix);
            rDlg.set(rPort.right + d(8), top, W - m, top + D); dialogue(c, rDlg, np, t0);
            float y = stat(c, top + D + d(10), W, m, np);
            rBoard.set(m, y + d(10), W - m, y + d(10) + d(92)); board(c, on, t0, dt);
            y = rBoard.bottom + d(10); buttons(c, y, W, m, on);
            artistBar(c, y + d(40) + d(10), W, m);
            rList.set(m, rArtist.bottom + d(10), W - m, H - m); list(c, l, np);
        }

        ArrayList<Song> songs() { return filtered(); }

        int rowAt(float y) {
            int i = (int) ((y - listT + off) / rowH);
            return y >= listT && i >= 0 && i < songs().size() ? i : -1;
        }

        final Runnable lp = new Runnable() { public void run() {
            if (mode != 1 || moved) return;
            int i = rowAt(downY); if (i < 0) return;
            lpFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            songMenu(new ArrayList<>(songs()), i);
        }};

        void act(int i) {
            beep(sSel);
            if (i == 0) prev(); else if (i == 1) toggle(); else if (i == 2) next(true);
            else if (i == 3) { pl.setShuffle(!pl.shuffle); say(pl.shuffle ? "* The wind shifts." : "* The wind dies down."); }
            else { pl.cycleRepeat(); say(pl.repeat == 1 ? "* You can't escape the loop." : pl.repeat == 2 ? "* Just one more time." : "* The loop ends."); }
        }

        float frac(float x) { return Math.max(0, Math.min(1, (x - rHp.left) / rHp.width())); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY(); long t0 = SystemClock.uptimeMillis();
            if (fight != null) return fight.touch(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mode = 0; moved = false; lpFired = false; downX = x; downY = y; lastY = y; pressed = -1; fl.down(e);
                    if (rTheme.contains(x, y)) mode = 5;
                    else if (rFont.contains(x, y)) mode = 9;
                    else if (rTitle.contains(x, y)) mode = 6;
                    else if (rPort.contains(x, y)) mode = 10;
                    else if (rDlg.contains(x, y)) mode = 11;
                    else if (!rHp.isEmpty() && x >= rHp.left - d(10) && x <= rHp.right + d(10) && y >= rHp.top - d(12) && y <= rHp.bottom + d(12)) { mode = 2; seekF = frac(x); }
                    else if (rBoard.contains(x, y)) { mode = 8; touchedAt = t0; sx = x; sy = y; }
                    else if (rBack.contains(x, y)) mode = 12;
                    else {
                        for (int i = 0; i < 5; i++) if (rb[i].contains(x, y)) { pressed = i; mode = 4; }
                        if (mode == 0 && rArtist.contains(x, y)) mode = 7;
                        if (mode == 0 && rList.contains(x, y)) { mode = 1; postDelayed(lp, ViewConfiguration.getLongPressTimeout()); }
                    }
                    invalidate(); return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mode == 2) seekF = frac(x);
                    else if (mode == 8) { touchedAt = t0; sx = x; sy = y; }
                    else if (mode == 1) {
                        if (Math.abs(y - downY) > d(8)) { moved = true; removeCallbacks(lp); }
                        if (moved) off -= y - lastY;
                        lastY = y;
                    }
                    else if (mode == 4 && pressed >= 0 && !rb[pressed].contains(x, y)) pressed = -1;
                    else if (mode == 5 && !rTheme.contains(x, y)) mode = 0;
                    else if (mode == 7 && !rArtist.contains(x, y)) mode = 0;
                    else if (mode == 9 && !rFont.contains(x, y)) mode = 0;
                    else if (mode == 12 && !rBack.contains(x, y)) mode = 0;
                    fl.move(e); invalidate(); return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    removeCallbacks(lp);
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    fl.up(e, up && mode == 1 && moved, this);
                    if (up && mode == 2 && pl.loaded() && dur() > 0) seekTo((int) (seekF * dur()));
                    seekF = -1;
                    if (up && mode == 5) { beep(sSel); mainMenu(); }
                    else if (up && mode == 9) { startFight(); }
                    else if (up && mode == 6) { beep(sSel); say("* You are filled with determination."); }
                    else if (up && mode == 10) {
                        beep(sSel);
                        if (poster) { pix = !pix; getPreferences(0).edit().putBoolean("utpix", pix).apply(); say(pix ? "* Sprite mode." : "* HD mode."); }
                        else if (now() != null) poster = true; else say("* (There's nothing to see here.)");
                    }
                    else if (up && mode == 11) { typedAt -= 100000; }
                    else if (up && mode == 12) { beep(sSel); poster = false; }
                    else if (up && mode == 7) { beep(sSel); pickFilter(); }
                    else if (up && mode == 4 && pressed >= 0) act(pressed);
                    else if (up && mode == 1 && !moved && !lpFired) { int i = rowAt(y); if (i >= 0) { beep(sSel); playList(songs(), i); } }
                    pressed = -1; mode = 0; invalidate(); return true;
                }
            }
            return true;
        }

        // =================================================================
        //  FIGHT: the song is the enemy and attacks on its own beats.
        //  Drag anywhere to move the SOUL (it follows your finger's movement, so your thumb never hides it).
        //  Tap FIGHT exactly on the beat for big damage. ACT checks the song, ITEM heals, MERCY leaves.
        // =================================================================
        Fight fight;

        void startFight() {
            beep(sSel);
            if (now() == null) toggle();
            if (now() == null) { say("* But nobody came."); return; }
            poster = false; fight = new Fight(); invalidate();
        }

        class Shot {
            int k; float x, y, vx, vy, a, b; long t0, t1; boolean hit;
            Shot(int k, float x, float y, float vx, float vy) { this.k = k; this.x = x; this.y = y; this.vx = vx; this.vy = vy; }
        }

        class Fight {
            static final int PELLET = 0, BONE = 1, BEAM = 2, SPEAR = 3, MAXHP = 20;
            int hp = MAXHP, items = 3, combo, best, score, dodged, gold, nextBeat, lastPos = -1, fmode = -1, fpressed = -1, wins;
            long invUntil, msgUntil, dmgAt, shakeUntil, flashUntil, overAt, nextTyped;
            String msg = "", dmgText = ""; int dmgCol = WHITE;
            Beats beats; long songId = Long.MIN_VALUE; String songTitle = "";
            final ArrayList<Shot> shots = new ArrayList<>();
            float sx = -1, sy, lx, ly, pulse;
            boolean over;
            final RectF rEnemy = new RectF(), rBoxF = new RectF(); final RectF[] fb = {new RectF(), new RectF(), new RectF(), new RectF()};
            final String[] NAMES = {"FIGHT", "ACT", "ITEM", "MERCY"};
            final String[] FOOD = {"* You ate the Butterscotch Pie. Your HP was maxed out.", "* You ate the Instant Noodles. You recovered 15 HP!", "* You ate a Snowman Piece. You recovered 10 HP!"};

            void tell(String s, long ms) { msg = s; msgUntil = SystemClock.uptimeMillis() + ms; }

            // ----- the song as an enemy -----
            void meet(final Song s, long t0) {
                if (songId != Long.MIN_VALUE && !over) {   // previous song ended with you alive = victory
                    wins++; int exp = 10 + score / 10; gold += 5 + dodged / 4;
                    tell("* YOU WON!  You earned " + exp + " EXP and " + (5 + dodged / 4) + " gold.", 3500);
                } else tell("* " + s.title + " draws near!", 3000);
                songId = s.id; songTitle = s.title; shots.clear(); nextBeat = 0; lastPos = -1;
                beats = Beats.cached(s.id);
                if (beats != null) { nextBeat = beats.after(pos()); lastPos = pos(); return; }
                final int du = dur(), at = pos();
                try {
                    beatEx.execute(new Runnable() { public void run() {
                        Beats.analyze(MainActivity.this, s, du, at, new Beats.Sink() { public void got(final Beats b) {
                            h.post(new Runnable() { public void run() {
                                if (fight == Fight.this && songId == s.id) { beats = b; nextBeat = b.after(pos()); lastPos = pos(); }
                            }});
                        }});
                    }});
                } catch (Exception e) { beats = Beats.fallback(s.id, du); }
            }

            void onBeat(float pw, int idx, long t0) {
                pulse = 1f; if (pw > .7f) flashUntil = t0 + 110;
                if (pw < .22f) return;
                float bl = rBoxF.left + d(4), br = rBoxF.right - d(4), bt = rBoxF.top + d(4), bb = rBoxF.bottom - d(4), bw = br - bl, bh = bb - bt;
                int pat = (idx / 16) % 5;   // the attack style changes every 16 beats
                if (pw > .9f && pat != 4 && rnd.nextInt(5) == 0) pat = 4;
                float sp = d(85) + d(150) * pw;
                switch (pat) {
                    case 0: {   // rain
                        int n = 1 + Math.round(pw * 3);
                        for (int i = 0; i < n; i++) { Shot s = new Shot(PELLET, bl + d(8) + rnd.nextFloat() * (bw - d(16)), bt - d(6), (rnd.nextFloat() - .5f) * d(30), sp); s.a = d(4); shots.add(s); }
                        break;
                    }
                    case 1: {   // bone walls with a gap
                        if (pw < .45f) { Shot s = new Shot(PELLET, bl + rnd.nextFloat() * bw, bt - d(6), 0, sp); s.a = d(4); shots.add(s); break; }
                        boolean left = (idx & 1) == 0; float gap = d(30) + d(14) * (1 - pw), gy = bt + gap / 2 + d(6) + rnd.nextFloat() * (bh - gap - d(12));
                        float x0 = left ? bl - d(8) : br + d(8), v = left ? sp : -sp;
                        float topLen = gy - gap / 2 - bt, botLen = bb - (gy + gap / 2);
                        if (topLen > d(4)) { Shot s = new Shot(BONE, x0, bt + topLen / 2, v, 0); s.a = topLen / 2; s.b = d(7); shots.add(s); }
                        if (botLen > d(4)) { Shot s = new Shot(BONE, x0, bb - botLen / 2, v, 0); s.a = botLen / 2; s.b = d(7); shots.add(s); }
                        break;
                    }
                    case 2: {   // ring burst from the enemy
                        int n = 6 + Math.round(pw * 8); float cx = (bl + br) / 2, cy = bt - d(10), off0 = rnd.nextFloat();
                        for (int i = 0; i < n; i++) {
                            double ang = Math.PI * (.12 + .76 * ((i + off0) / n));
                            Shot s = new Shot(PELLET, cx, cy, (float) Math.cos(ang) * sp * .8f, (float) Math.sin(ang) * sp * .8f); s.a = d(3.5f); shots.add(s);
                        }
                        break;
                    }
                    case 3: {   // spears aimed at you
                        int n = pw > .75f ? 2 : 1;
                        for (int i = 0; i < n; i++) {
                            int side = rnd.nextInt(4); float x = side == 0 ? bl - d(10) : side == 1 ? br + d(10) : bl + rnd.nextFloat() * bw, y = side == 2 ? bt - d(10) : side == 3 ? bb + d(10) : bt + rnd.nextFloat() * bh;
                            float dx = sx - x, dy = sy - y, len = (float) Math.max(1, Math.sqrt(dx * dx + dy * dy));
                            Shot s = new Shot(SPEAR, x, y, dx / len * sp * 1.15f, dy / len * sp * 1.15f); shots.add(s);
                        }
                        break;
                    }
                    default: {  // blaster beam: warning line, then fire
                        if (pw < .55f) { Shot s = new Shot(PELLET, bl + rnd.nextFloat() * bw, bt - d(6), 0, sp); s.a = d(4); shots.add(s); break; }
                        boolean horiz = rnd.nextBoolean(); Shot s = new Shot(BEAM, horiz ? 0 : sx, horiz ? sy : 0, 0, 0);
                        s.a = horiz ? 1 : 0; s.b = d(16) + d(16) * pw; s.t0 = t0 + 480; s.t1 = s.t0 + 380; shots.add(s);
                    }
                }
            }

            void hurt(int dmg, long t0) {
                if (t0 < invUntil || over) return;
                hp = Math.max(0, hp - dmg); invUntil = t0 + 900; shakeUntil = t0 + 220; combo = 0; beep(sHit);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (hp == 0) { over = true; overAt = t0; shots.clear(); }
            }

            void update(long t0, float dt) {
                Song s = now();
                if (s == null) return;
                if (s.id != songId) meet(s, t0);
                pulse = Math.max(0, pulse - dt * 4);
                int p = pos();
                if (beats != null && !over) {
                    if (lastPos >= 0 && (p < lastPos - 400 || p > lastPos + 1500)) nextBeat = beats.after(p);   // seeked
                    int fired = 0;
                    while (nextBeat < beats.at.length && beats.at[nextBeat] <= p) { if (fired++ < 3) onBeat(beats.power[nextBeat], nextBeat, t0); nextBeat++; }
                    lastPos = p;
                }
                float bl = rBoxF.left + d(4), br = rBoxF.right - d(4), bt = rBoxF.top + d(4), bb = rBoxF.bottom - d(4), sr = d(5);
                for (int i = shots.size() - 1; i >= 0; i--) {
                    Shot o = shots.get(i);
                    if (o.k == BEAM) {
                        if (t0 > o.t1) { shots.remove(i); if (!o.hit) { dodged++; score += 3; } continue; }
                        if (t0 >= o.t0 && (o.a == 1 ? Math.abs(o.y - sy) : Math.abs(o.x - sx)) < o.b / 2 + sr * .6f) { o.hit = true; hurt(6, t0); }
                        continue;
                    }
                    o.x += o.vx * dt; o.y += o.vy * dt;
                    boolean in;
                    if (o.k == BONE) in = Math.abs(o.x - sx) < o.b / 2 + sr * .7f && Math.abs(o.y - sy) < o.a + sr * .7f;
                    else { float dx = o.x - sx, dy = o.y - sy, rr = (o.k == SPEAR ? d(5) : o.a) + sr * .7f; in = dx * dx + dy * dy < rr * rr; }
                    if (in && !o.hit) { o.hit = true; hurt(o.k == BONE ? 4 : 3, t0); shots.remove(i); continue; }
                    float m = d(40);
                    if (o.x < bl - m || o.x > br + m || o.y < bt - m || o.y > bb + m) { shots.remove(i); if (!o.hit) { dodged++; score += 1; } }
                }
            }

            // ----- drawing -----
            void draw(Canvas c, float W, float H, long t0, float dt) {
                float m = d(12);
                // layout
                float es = Math.min(W * .46f, H * .22f), top = d(32);
                rEnemy.set((W - es) / 2, top, (W + es) / 2, top + es);
                float btnH = d(46), btnY = H - m - btnH, statY = btnY - d(12) - d(20);
                float msgEnd = rEnemy.bottom + d(10) + d(26) + ts(M) * 1.4f * 2;
                rBoxF.set(m, Math.min(msgEnd + d(6), statY - d(10) - d(150)), W - m, statY - d(10));
                if (sx < 0) { sx = rBoxF.centerX(); sy = rBoxF.centerY(); }
                sx = Math.max(rBoxF.left + d(10), Math.min(rBoxF.right - d(10), sx)); sy = Math.max(rBoxF.top + d(10), Math.min(rBoxF.bottom - d(10), sy));
                update(t0, dt);
                Song s = now();
                c.save();
                if (t0 < shakeUntil) c.translate((rnd.nextFloat() - .5f) * d(6), (rnd.nextFloat() - .5f) * d(6));
                if (over) { gameOver(c, W, H, t0); c.restore(); return; }

                // enemy: the cover as a sprite, bouncing to the beat
                float sc = 1 + pulse * .09f, bob = (float) Math.sin(t0 / 380.0) * d(3);
                tmp.set(rEnemy.centerX() - es * sc / 2, rEnemy.centerY() - es * sc / 2 + bob, rEnemy.centerX() + es * sc / 2, rEnemy.centerY() + es * sc / 2 + bob);
                cover(c, tmp, s, 32, true);
                if (t0 - dmgAt < 320 && dmgCol != DG) {   // slash
                    float k = (t0 - dmgAt) / 320f; p.setColor(RD); p.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 3; i++) { float o = (i - 1) * d(10); c.save(); c.rotate(-35, tmp.centerX(), tmp.centerY()); c.drawRect(tmp.centerX() - d(2) + o, tmp.top + tmp.height() * (k - .3f), tmp.centerX() + d(2) + o, tmp.top + tmp.height() * k, p); c.restore(); }
                }
                tx(c, s == null ? "" : s.title, W / 2, d(16), S, WHITE, Paint.Align.CENTER, W - 2 * m);
                // enemy HP = how much of the song is left
                float du = Math.max(1, dur()), left = 1 - Math.min(1, pos() / du), hw = Math.min(W - 2 * m, d(160));
                float hy = rEnemy.bottom + d(10);
                p.setStyle(Paint.Style.FILL); p.setColor(RD); c.drawRect(W / 2 - hw / 2, hy, W / 2 + hw / 2, hy + d(8), p);
                p.setColor(0xFF00C000); c.drawRect(W / 2 - hw / 2, hy, W / 2 - hw / 2 + hw * left, hy + d(8), p);
                if (t0 - dmgAt < 900) {
                    float k = (t0 - dmgAt) / 900f;
                    tx(c, dmgText, W / 2, rEnemy.top + es * .25f - k * d(26), L, dmgCol, Paint.Align.CENTER, 0);
                }
                // message line
                String line = t0 < msgUntil ? msg : beats == null ? "* The music is gathering power..." : combo >= 3 ? "* COMBO x" + combo + "!  Keep hitting the beat." : "* " + songTitle + " attacks to the beat!";
                float my = hy + d(26);
                for (UL u : wrap(line, M, W - 2 * m, 2, WHITE)) { tx(c, u.s, m, my, M, WHITE, Paint.Align.LEFT, W - 2 * m); my += ts(M) * 1.4f; }

                // the bullet board
                boolean flash = t0 < flashUntil;
                ubox(c, rBoxF, flash ? YE : WHITE, d(4));
                c.save(); c.clipRect(rBoxF.left + d(4), rBoxF.top + d(4), rBoxF.right - d(4), rBoxF.bottom - d(4));
                for (Shot o : shots) drawShot(c, o, t0);
                c.restore();
                boolean blink = t0 < invUntil && ((t0 / 90) & 1) == 0;
                if (!blink) heart(c, sx, sy, d(1.9f), RD);

                // stats
                float cy = statY + d(10);
                String nm = "TAPE  LV " + Math.min(20, 1 + wins);
                tx(c, nm, m, cy, M, WHITE, Paint.Align.LEFT, 0);
                float hx = m + tw(nm, M) + d(12); tx(c, "HP", hx, cy, S, WHITE, Paint.Align.LEFT, 0);
                float bx0 = hx + tw("HP", S) + d(6), bw = d(4) * MAXHP / 2f;
                p.setColor(RD); c.drawRect(bx0, cy - d(8), bx0 + bw, cy + d(8), p);
                p.setColor(YE); c.drawRect(bx0, cy - d(8), bx0 + bw * hp / MAXHP, cy + d(8), p);
                tx(c, hp + " / " + MAXHP, bx0 + bw + d(8), cy, M, WHITE, Paint.Align.LEFT, 0);
                tx(c, "SCORE " + score, W - m, cy, S, GR, Paint.Align.RIGHT, 0);

                // FIGHT ACT ITEM MERCY
                float gw = (W - 2 * m - 3 * d(8)) / 4;
                for (int i = 0; i < 4; i++) {
                    fb[i].set(m + i * (gw + d(8)), btnY, m + i * (gw + d(8)) + gw, btnY + btnH);
                    boolean sel = fpressed == i; int col = sel ? YE : OR;
                    ubox(c, fb[i], col, d(3));
                    String lbl = NAMES[i];
                    float tw0 = tw(lbl, M), hwid = sel ? d(12) : 0, x = fb[i].centerX() - (tw0 + hwid) / 2;
                    if (sel) heart(c, x + d(4), fb[i].centerY(), d(1.1f), RD);
                    tx(c, lbl, x + hwid, fb[i].centerY(), M, col, Paint.Align.LEFT, gw - d(6));
                }
                c.restore();
                postInvalidateOnAnimation();   // smooth 60 fps while fighting
            }

            void drawShot(Canvas c, Shot o, long t0) {
                p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(WHITE);
                if (o.k == PELLET) c.drawCircle(o.x, o.y, o.a, p);
                else if (o.k == BONE) {
                    float hw = o.b / 2, r = o.b * .45f;
                    c.drawRect(o.x - hw * .55f, o.y - o.a, o.x + hw * .55f, o.y + o.a, p);
                    c.drawCircle(o.x - r * .6f, o.y - o.a, r, p); c.drawCircle(o.x + r * .6f, o.y - o.a, r, p);
                    c.drawCircle(o.x - r * .6f, o.y + o.a, r, p); c.drawCircle(o.x + r * .6f, o.y + o.a, r, p);
                } else if (o.k == SPEAR) {
                    float len = (float) Math.max(1, Math.sqrt(o.vx * o.vx + o.vy * o.vy)), ux = o.vx / len, uy = o.vy / len, L0 = d(16);
                    p.setColor(0xFF39B9FF); p.setStrokeWidth(d(3)); p.setStyle(Paint.Style.STROKE);
                    c.drawLine(o.x - ux * L0, o.y - uy * L0, o.x, o.y, p); p.setStyle(Paint.Style.FILL);
                    Path ph = new Path(); ph.moveTo(o.x + ux * d(7), o.y + uy * d(7)); ph.lineTo(o.x - uy * d(5), o.y + ux * d(5)); ph.lineTo(o.x + uy * d(5), o.y - ux * d(5)); ph.close(); c.drawPath(ph, p);
                } else if (o.k == BEAM) {
                    boolean live = t0 >= o.t0; float hw = live ? o.b / 2 * (1 - Math.max(0, (t0 - o.t0) / (float) (o.t1 - o.t0) - .6f) * 2.5f) : d(1.5f);
                    p.setColor(live ? WHITE : ((t0 / 60) % 2 == 0 ? YE : RD));
                    if (o.a == 1) c.drawRect(rBoxF.left, o.y - hw, rBoxF.right, o.y + hw, p); else c.drawRect(o.x - hw, rBoxF.top, o.x + hw, rBoxF.bottom, p);
                    if (!live) {   // the "blaster" head at the edge
                        float hx = o.a == 1 ? rBoxF.left + d(14) : o.x, hy = o.a == 1 ? o.y : rBoxF.top + d(14);
                        p.setColor(WHITE); c.drawRect(hx - d(8), hy - d(8), hx + d(8), hy + d(8), p);
                        p.setColor(BLACK); c.drawRect(hx - d(5), hy - d(3), hx - d(2), hy, p); c.drawRect(hx + d(2), hy - d(3), hx + d(5), hy, p);
                    }
                }
            }

            void gameOver(Canvas c, float W, float H, long t0) {
                long k = t0 - overAt; float cx = W / 2, cy = H * .42f;
                if (k < 900) heart(c, cx, cy, d(3), RD);
                else if (k < 1500) {   // broken heart
                    p.setColor(RD); p.setStyle(Paint.Style.FILL);
                    c.save(); c.clipRect(0, 0, cx - d(1), H); heart(c, cx - d(3), cy, d(3), RD); c.restore();
                    c.save(); c.clipRect(cx + d(1), 0, W, H); heart(c, cx + d(3), cy, d(3), RD); c.restore();
                } else {
                    float f = Math.min(1, (k - 1500) / 1200f);
                    for (int i = 0; i < 6; i++) { double ang = i * 1.05 + .3; float r = f * d(90); heart(c, cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r + f * f * d(120), d(.9f), RD); }
                    if (k > 2200) {
                        if (logoFore != null) logo(c, "GAME OVER", cx, H * .30f, 34, Paint.Align.CENTER, W - d(24), 0xFF555555, WHITE);
                        else tx(c, "GAME OVER", cx, H * .30f, L, WHITE, Paint.Align.CENTER, 0);
                        tx(c, "* Don't lose hope...", cx, H * .55f, M, WHITE, Paint.Align.CENTER, W - d(24));
                        tx(c, "* Stay determined!", cx, H * .55f + d(28), M, WHITE, Paint.Align.CENTER, W - d(24));
                        tx(c, "SCORE " + score + "   BEST COMBO " + best, cx, H * .55f + d(70), S, GR, Paint.Align.CENTER, 0);
                        tx(c, "(tap to continue)", cx, H * .55f + d(100), S, DG, Paint.Align.CENTER, 0);
                    }
                }
            }

            // ----- input -----
            void press(int i, long t0) {
                beep(sSel);
                if (i == 0) {   // FIGHT: rhythm hit
                    if (beats == null) { tell("* You can't find the rhythm yet.", 1500); return; }
                    int off = beats.nearest(pos()); beep(sSlash); dmgAt = t0;
                    if (off < 110) { combo++; int dmg = 40 + combo * 4 + rnd.nextInt(10); score += dmg; dmgText = "" + dmg; dmgCol = RD; tell(combo > 1 ? "* PERFECT!  x" + combo : "* PERFECT!", 900); }
                    else if (off < 220) { combo++; int dmg = 12 + combo + rnd.nextInt(6); score += dmg; dmgText = "" + dmg; dmgCol = RD; tell("* Good hit.", 800); }
                    else { combo = 0; dmgText = "MISS"; dmgCol = DG; tell("* Hit on the beat!", 900); }
                    best = Math.max(best, combo);
                } else if (i == 1) {
                    Song s = now();
                    tell("* " + (s == null ? "???" : s.title.toUpperCase(Locale.US)) + " - ATK " + (beats == null ? "?" : beats.bpm) + "  DEF 0.  * " + (beats == null ? "It's warming up." : beats.real ? "It's in the groove." : "It hums a steady beat."), 3500);
                } else if (i == 2) {
                    if (items <= 0) { tell("* Your pockets are empty.", 1800); return; }
                    int heal = items == 3 ? MAXHP : items == 2 ? 15 : 10; String t = FOOD[3 - items]; items--;
                    hp = Math.min(MAXHP, hp + heal); tell(t, 2600);
                } else {
                    fight = null; say("* You spared " + songTitle + ".  (score " + score + ")");
                }
            }

            boolean touch(MotionEvent e) {
                float x = e.getX(), y = e.getY(); long t0 = SystemClock.uptimeMillis();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (over) { if (t0 - overAt > 2200) { over = false; hp = MAXHP; items = Math.max(items, 1); combo = 0; invUntil = t0 + 1500; tell("* You're filled with DETERMINATION.", 2500); } return true; }
                        fpressed = -1; fmode = 0;
                        for (int i = 0; i < 4; i++) if (fb[i].contains(x, y)) { fpressed = i; fmode = 1; }
                        lx = x; ly = y; invalidate(); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (fmode == 0) {   // relative drag: the SOUL moves with your finger, wherever you touch
                            sx += (x - lx) * 1.15f; sy += (y - ly) * 1.15f; lx = x; ly = y;
                        } else if (fpressed >= 0 && !fb[fpressed].contains(x, y)) fpressed = -1;
                        return true;
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && fmode == 1 && fpressed >= 0) press(fpressed, t0);
                        fpressed = -1; fmode = -1; invalidate(); return true;
                }
                return true;
            }
        }
    }

    // =====================================================================
    //  iTunes 2011 (iPhone OS 4 era): glossy nav bar, blue segmented control, numbered rows with
    //  cover art and a price-style pill (shows the length; turns green while playing), black iPod toolbar.
    // =====================================================================
    class ITunes extends View implements Scrolly {
        final TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF tmp = new RectF(), rBack = new RectF(), rMenu = new RectF(), rSegAll = new RectF(), rList = new RectF(), rSeek = new RectF(), rFav = new RectF(), rPanel = new RectF();
        final RectF[] rSeg = {new RectF(), new RectF(), new RectF()}, rCtl = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
        final Fling fl = new Fling();
        int tab, drill = -1, backTab, mode, pressed = -1, pressRow = -1;
        float off, rowH, listT, listB, downY, lastY, seekF = -1; boolean moved, lpFired;
        static final int NAV0 = 0xFFB2BFD0, NAV1 = 0xFF8C9EB7, NAV2 = 0xFF6D84A2;

        ITunes(Context c) { super(c); }

        public void top() { off = 0; fl.stop(); backTab = drill >= 0 ? drill : 0; drill = -1; if (fk != F_ALL) tab = 0; }

        boolean back() {
            if (fk != F_ALL) { int b = backTab; setFilter(F_ALL, null); tab = b; return true; }
            if (tab != 0) { tab = 0; top(); invalidate(); return true; }
            return false;
        }

        int count() { return tab == 0 ? filtered().size() : tab == 1 ? artistNames().size() : albumNames().size(); }

        void shadowText(Canvas c, String s, float x, float y, float size, int col, int sh, float dy, boolean bold, Paint.Align al, float maxW) {
            p.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); p.setTextSize(size); p.setTextAlign(al); p.setShader(null); p.setStyle(Paint.Style.FILL);
            if (maxW > 0) s = TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString();
            p.setColor(sh); c.drawText(s, x, y + dy, p);
            p.setColor(col); c.drawText(s, x, y, p);
        }

        void barButton(Canvas c, RectF r, String s, boolean arrow, boolean down) {
            Path ph = new Path(); float rad = d(5), ah = arrow ? r.height() * .42f : 0;
            if (arrow) {
                ph.moveTo(r.left, r.centerY()); ph.lineTo(r.left + ah, r.top); ph.lineTo(r.right - rad, r.top); ph.quadTo(r.right, r.top, r.right, r.top + rad);
                ph.lineTo(r.right, r.bottom - rad); ph.quadTo(r.right, r.bottom, r.right - rad, r.bottom); ph.lineTo(r.left + ah, r.bottom); ph.close();
            } else ph.addRoundRect(r, rad, rad, Path.Direction.CW);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, down ? new int[]{0xFF5F769A, 0xFF425C84, 0xFF3A5479} : new int[]{0xFF9AABC3, 0xFF6F86A8, 0xFF56719A}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
            c.drawPath(ph, p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(0xFF3B4A62); c.drawPath(ph, p); p.setStyle(Paint.Style.FILL);
            shadowText(c, s, r.centerX() + ah / 3, r.centerY() + d(4.5f), d(12.5f), WHITE, 0xFF3A4860, -d(1), true, Paint.Align.CENTER, r.width() - ah - d(8));
        }

        void pill(Canvas c, RectF r, String s, int c0, int c1, int border) {
            p.setStyle(Paint.Style.FILL); p.setShader(lg(0, r.top, 0, r.bottom, c0, c1)); c.drawRoundRect(r, d(5), d(5), p); p.setShader(null);
            p.setColor(0x33FFFFFF); c.drawRoundRect(r.left + d(1), r.top + d(1), r.right - d(1), r.centerY(), d(4), d(4), p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(border); c.drawRoundRect(r, d(5), d(5), p); p.setStyle(Paint.Style.FILL);
            shadowText(c, s, r.centerX(), r.centerY() + d(4.5f), d(s.length() > 5 ? 11 : 13), WHITE, 0x66000000, -d(1), true, Paint.Align.CENTER, r.width() - d(4));
        }

        void artBox(Canvas c, RectF r, Song s) {
            Bitmap a = artFor(s);
            if (a != null) { p.setFilterBitmap(true); c.drawBitmap(a, new Rect(0, 0, a.getWidth(), a.getHeight()), r, p); p.setFilterBitmap(false); return; }
            p.setShader(lg(0, r.top, 0, r.bottom, 0xFFD9DAE2, 0xFFB5B8C4)); c.drawRect(r, p); p.setShader(null);
            shadowText(c, "♫", r.centerX(), r.centerY() + r.height() * .16f, r.height() * .45f, 0xFF8D92A2, WHITE, d(1), false, Paint.Align.CENTER, 0);
        }

        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), m = d(8);
            p.reset(); p.setAntiAlias(true);
            c.drawColor(0xFFE6E6EE);
            Song np = now(); boolean on = playing();

            // ----- nav bar -----
            float navH = d(46);
            p.setShader(new LinearGradient(0, 0, 0, navH, new int[]{NAV0, NAV1, NAV2}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP)); c.drawRect(0, 0, W, navH, p); p.setShader(null);
            p.setColor(0x55FFFFFF); c.drawRect(0, 0, W, d(1), p);
            p.setColor(0xFF2D3642); c.drawRect(0, navH - d(1), W, navH, p);
            String title = fk != F_ALL ? label(fk, fv) : tab == 1 ? "Artists" : tab == 2 ? "Albums" : "iTunes";
            shadowText(c, title, W / 2, navH / 2 + d(7), d(20), WHITE, 0xFF4A5A72, -d(1), true, Paint.Align.CENTER, W - d(200));
            boolean filtered = fk != F_ALL;
            String bl = filtered ? new String[]{"Songs", "Artists", "Albums"}[backTab] : "Browse";
            p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(d(12.5f));
            float bw = Math.min(d(96), p.measureText(bl) + d(filtered ? 26 : 20));
            rBack.set(m, d(8), m + bw, navH - d(8)); barButton(c, rBack, bl, filtered, mode == 3);
            rMenu.set(W - m - d(58), d(8), W - m, navH - d(8)); barButton(c, rMenu, "Menu", false, mode == 4);

            // ----- segmented control -----
            float segT = navH, segB = segT + d(50);
            p.setShader(lg(0, segT, 0, segB, 0xFFE7E8EF, 0xFFD3D4DD)); c.drawRect(0, segT, W, segB, p); p.setShader(null);
            rSegAll.set(d(10), segT + d(9), W - d(10), segB - d(9));
            Path clip = new Path(); clip.addRoundRect(rSegAll, d(8), d(8), Path.Direction.CW);
            c.save(); c.clipPath(clip);
            float sw = rSegAll.width() / 3; String[] sn = {"Songs", "Artists", "Albums"};
            for (int i = 0; i < 3; i++) {
                rSeg[i].set(rSegAll.left + i * sw, rSegAll.top, rSegAll.left + (i + 1) * sw, rSegAll.bottom);
                boolean sel = tab == i, dn = mode == 5 && pressed == i;
                p.setShader(new LinearGradient(0, rSegAll.top, 0, rSegAll.bottom, sel ? new int[]{0xFF7DAEF5, 0xFF3C7FE3, 0xFF2363D1, 0xFF1D5BC8} : dn ? new int[]{0xFFDADBE2, 0xFFC4C6CF, 0xFFBABCC6, 0xFFB3B5C0} : new int[]{0xFFFFFFFF, 0xFFF0F0F4, 0xFFDEDFE5, 0xFFD3D4DC},
                    new float[]{0, .5f, .5f, 1}, Shader.TileMode.CLAMP));
                c.drawRect(rSeg[i], p); p.setShader(null);
                shadowText(c, sn[i], rSeg[i].centerX(), rSeg[i].centerY() + d(5.5f), d(15), sel ? WHITE : 0xFF4D596D, sel ? 0xFF1B3E7E : 0xCCFFFFFF, sel ? -d(1) : d(1), true, Paint.Align.CENTER, sw - d(6));
                if (i > 0) { p.setColor(0xFF7E8798); c.drawRect(rSeg[i].left - d(.5f), rSegAll.top, rSeg[i].left + d(.5f), rSegAll.bottom, p); }
            }
            c.restore();
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1.2f)); p.setColor(0xFF7A8395); c.drawRoundRect(rSegAll, d(8), d(8), p); p.setStyle(Paint.Style.FILL);

            // ----- hint band -----
            float hb = segB + d(26);
            p.setShader(lg(0, segB, 0, hb, 0xFFD3D4E1, 0xFFC7C9D8)); c.drawRect(0, segB, W, hb, p); p.setShader(null);
            p.setColor(0xFFA9ABBC); c.drawRect(0, hb - d(1), W, hb, p);
            String hint = tab == 0 ? (count() == 0 ? " " : "Tap to Play, Hold for More") : "Tap to View Songs";
            shadowText(c, hint, W / 2, segB + d(17.5f), d(13), 0xFF4E5568, 0xAAFFFFFF, d(1), false, Paint.Align.CENTER, W - d(20));

            // ----- bottom "iPod" toolbar -----
            float panelH = d(156); rPanel.set(0, H - panelH, W, H);
            p.setShader(new LinearGradient(0, rPanel.top, 0, H, new int[]{0xFF545454, 0xFF2A2A2A, 0xFF141414, 0xFF000000}, new float[]{0, .32f, .32f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(rPanel, p); p.setShader(null);
            p.setColor(0xFF8A8A8A); c.drawRect(0, rPanel.top, W, rPanel.top + d(1), p);

            // ----- list -----
            listT = hb; listB = rPanel.top; rList.set(0, listT, W, listB); rowH = d(66);
            int n = count();
            float viewH = listB - listT, maxOff = Math.max(0, n * rowH - viewH);
            off = fl.apply(off, maxOff, this);
            c.save(); c.clipRect(rList);
            if (n == 0) {
                shadowText(c, all.isEmpty() ? "No Music" : "No Results", W / 2, listT + d(80), d(19), 0xFF6B7080, WHITE, d(1), true, Paint.Align.CENTER, 0);
                shadowText(c, all.isEmpty() ? "Add songs to your phone, then Menu > Refresh" : "Tap " + bl + " to go back", W / 2, listT + d(104), d(13), 0xFF8A8F9E, WHITE, d(1), false, Paint.Align.CENTER, W - d(20));
            }
            ArrayList<Song> songs = tab == 0 ? filtered() : null; ArrayList<String> names = tab == 1 ? artistNames() : tab == 2 ? albumNames() : null;
            for (int i = Math.max(0, (int) (off / rowH)); i < n; i++) {
                float ry = listT + i * rowH - off; if (ry > listB) break;
                boolean dn = mode == 1 && pressRow == i && !moved;
                p.setShader(lg(0, ry, 0, ry + rowH, dn ? 0xFF5B8FE6 : 0xFFF2F2F8, dn ? 0xFF2D63CC : 0xFFE1E1EA)); c.drawRect(0, ry, W, ry + rowH, p); p.setShader(null);
                p.setColor(0xCCFFFFFF); c.drawRect(0, ry, W, ry + d(1), p);
                p.setColor(0xFFB2B4C3); c.drawRect(0, ry + rowH - d(1), W, ry + rowH, p);
                tmp.set(0, ry, rowH - d(1), ry + rowH - d(1));
                float x = rowH + d(10), tw = W - x - d(86);
                int tc = dn ? WHITE : BLACK, sc = dn ? 0xFFE2EAFF : 0xFF6C7182;
                if (tab == 0) {
                    Song s = songs.get(i); boolean cu = s.equals(np);
                    artBox(c, tmp, s);
                    shadowText(c, (i + 1) + ". " + s.title, x, ry + d(28), d(15.5f), tc, dn ? 0x00000000 : 0x00FFFFFF, 0, true, Paint.Align.LEFT, tw);
                    float ax = x;
                    if (fav(s)) { shadowText(c, "♥", ax, ry + d(49), d(13), 0xFFE0245E, 0, 0, true, Paint.Align.LEFT, 0); ax += d(15); }
                    shadowText(c, s.artist, ax, ry + d(49), d(13.5f), sc, 0, 0, false, Paint.Align.LEFT, tw - (ax - x));
                    tmp.set(W - d(10) - d(66), ry + rowH / 2 - d(14), W - d(10), ry + rowH / 2 + d(14));
                    if (cu && on) pill(c, tmp, "PLAYING", 0xFF8ED46B, 0xFF3F9B23, 0xFF2E7518);
                    else if (cu) pill(c, tmp, "PLAY", 0xFF79AAF2, 0xFF2D69D2, 0xFF22509F);
                    else pill(c, tmp, fmt(s.dur), 0xFF99A1B1, 0xFF6E778A, 0xFF59627A);
                } else {
                    String name = names.get(i); Song rep = repOf(tab == 1, name);
                    artBox(c, tmp, rep);
                    shadowText(c, name, x, ry + d(28), d(15.5f), tc, 0, 0, true, Paint.Align.LEFT, W - x - d(34));
                    String sub = tab == 1 ? songCount(name) + (songCount(name) == 1 ? " song" : " songs") : (rep == null ? "" : rep.artist);
                    shadowText(c, sub, x, ry + d(49), d(13.5f), sc, 0, 0, false, Paint.Align.LEFT, W - x - d(34));
                    float cx = W - d(18), cy = ry + rowH / 2;
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(2.6f)); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(dn ? WHITE : 0xFF7F8596);
                    Path ch = new Path(); ch.moveTo(cx - d(4), cy - d(7)); ch.lineTo(cx + d(3), cy); ch.lineTo(cx - d(4), cy + d(7)); c.drawPath(ch, p);
                    p.setStyle(Paint.Style.FILL); p.setStrokeCap(Paint.Cap.BUTT);
                }
            }
            c.restore();
            if (maxOff > 0) {
                float tk = Math.max(d(30), viewH * viewH / (n * rowH)), ty = listT + (viewH - tk) * off / maxOff;
                p.setColor(0x66000000); c.drawRoundRect(W - d(6), ty + d(2), W - d(2), ty + tk - d(2), d(2), d(2), p);
            }
            // inner shadow under the hint band
            p.setShader(lg(0, listT, 0, listT + d(6), 0x33000000, 0x00000000)); c.drawRect(0, listT, W, listT + d(6), p); p.setShader(null);

            // ----- now playing (in the black toolbar) -----
            float y0 = rPanel.top;
            tmp.set(m + d(4), y0 + d(10), m + d(4) + d(46), y0 + d(56));
            if (np != null) artBox(c, tmp, np); else { p.setColor(0xFF333333); c.drawRect(tmp, p); }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(0xFF666666); c.drawRect(tmp, p); p.setStyle(Paint.Style.FILL);
            float tx0 = tmp.right + d(10), tmax = W - tx0 - d(56);
            shadowText(c, np == null ? "Not Playing" : np.title, tx0, y0 + d(30), d(15), WHITE, BLACK, -d(1), true, Paint.Align.LEFT, tmax);
            shadowText(c, np == null ? "Pick a song" : np.artist + "  —  " + np.album, tx0, y0 + d(49), d(12.5f), 0xFFB4B4B4, BLACK, -d(1), false, Paint.Align.LEFT, tmax);
            rFav.set(W - m - d(44), y0 + d(12), W - m, y0 + d(54));
            boolean fv0 = fav(np);
            shadowText(c, fv0 ? "♥" : "♡", rFav.centerX(), rFav.centerY() + d(9), d(26), fv0 ? 0xFFFF4D7D : 0xFF9A9A9A, BLACK, -d(1), true, Paint.Align.CENTER, 0);

            // scrubber
            int du = dur(), ps = pos(); float f = seekF >= 0 ? seekF : (du > 0 ? Math.min(1f, ps / (float) du) : 0); if (seekF >= 0) ps = (int) (seekF * du);
            float sy = y0 + d(76); rSeek.set(m + d(44), sy - d(4.5f), W - m - d(44), sy + d(4.5f));
            shadowText(c, fmt(ps), m + d(38), sy + d(4), d(11.5f), 0xFFCFCFCF, BLACK, -d(1), true, Paint.Align.RIGHT, 0);
            shadowText(c, "-" + fmt(du - ps), W - m - d(38), sy + d(4), d(11.5f), 0xFFCFCFCF, BLACK, -d(1), true, Paint.Align.LEFT, 0);
            p.setShader(lg(0, rSeek.top, 0, rSeek.bottom, 0xFF0E0E0E, 0xFF505050)); c.drawRoundRect(rSeek, d(4.5f), d(4.5f), p);
            float fx = rSeek.left + rSeek.width() * f;
            p.setShader(lg(0, rSeek.top, 0, rSeek.bottom, 0xFF9FCBFF, 0xFF3C7BE6, 0xFF1F5ACB)); c.drawRoundRect(rSeek.left, rSeek.top, Math.max(rSeek.left + d(9), fx), rSeek.bottom, d(4.5f), d(4.5f), p); p.setShader(null);
            p.setShader(lg(0, sy - d(10), 0, sy + d(10), 0xFFFFFFFF, 0xFFBDBDBD)); c.drawCircle(fx, sy, d(10), p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(0xFF6E6E6E); c.drawCircle(fx, sy, d(10), p); p.setStyle(Paint.Style.FILL);

            // controls: repeat, prev, play, next, shuffle
            float cy = y0 + d(122), cw = (W - 2 * m) / 5;
            for (int i = 0; i < 5; i++) {
                rCtl[i].set(m + i * cw, cy - d(26), m + (i + 1) * cw, cy + d(26));
                float cx = rCtl[i].centerX();
                if (mode == 6 && pressed == i) { p.setShader(new RadialGradient(cx, cy, d(26), 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)); c.drawCircle(cx, cy, d(26), p); p.setShader(null); }
                boolean act = (i == 0 && pl.repeat != 0) || (i == 4 && pl.shuffle);
                int col = act ? 0xFF56AEFF : WHITE;
                p.setColor(col); p.setStyle(Paint.Style.FILL);
                if (i == 1) { c.drawRect(cx - d(13), cy - d(9), cx - d(10), cy + d(9), p); tri(c, p, cx - d(3), cy, d(15), -1); tri(c, p, cx + d(8), cy, d(15), -1); }
                else if (i == 3) { tri(c, p, cx - d(8), cy, d(15), 1); tri(c, p, cx + d(3), cy, d(15), 1); c.drawRect(cx + d(10), cy - d(9), cx + d(13), cy + d(9), p); }
                else if (i == 2) { if (on) { c.drawRect(cx - d(9), cy - d(12), cx - d(3), cy + d(12), p); c.drawRect(cx + d(3), cy - d(12), cx + d(9), cy + d(12), p); } else tri(c, p, cx + d(2), cy, d(24), 1); }
                else if (i == 0) {
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(2.2f)); tmp.set(cx - d(11), cy - d(7), cx + d(11), cy + d(7)); c.drawRoundRect(tmp, d(5), d(5), p); p.setStyle(Paint.Style.FILL);
                    tri(c, p, cx + d(3), cy - d(7), d(7), 1);
                    if (pl.repeat == 2) shadowText(c, "1", cx, cy + d(4), d(10), col, 0, 0, true, Paint.Align.CENTER, 0);
                } else {
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(2.2f));
                    c.drawLine(cx - d(11), cy - d(6), cx + d(7), cy + d(6), p); c.drawLine(cx - d(11), cy + d(6), cx + d(7), cy - d(6), p); p.setStyle(Paint.Style.FILL);
                    tri(c, p, cx + d(9), cy - d(6), d(7), 1); tri(c, p, cx + d(9), cy + d(6), d(7), 1);
                }
            }
            if (pl.sleepAt > 0 || pl.sleepEndOfSong) shadowText(c, "☾ " + pl.sleepLabel(), W - m, y0 + d(96), d(10.5f), 0xFF9A9A9A, BLACK, -d(1), false, Paint.Align.RIGHT, 0);
        }

        int rowAt(float y) { int i = (int) ((y - listT + off) / rowH); return y >= listT && y < listB && i >= 0 && i < count() ? i : -1; }

        final Runnable lp = new Runnable() { public void run() {
            if (mode != 1 || moved || tab != 0) return;
            int i = rowAt(downY); if (i < 0) return;
            lpFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); invalidate();
            songMenu(new ArrayList<>(filtered()), i);
        }};

        void tapRow(int i, float x) {
            if (tab == 0) {
                ArrayList<Song> l = filtered(); if (i >= l.size()) return;
                if (l.get(i).equals(now()) && x > getWidth() - d(90)) toggle(); else playList(l, i);
            } else {
                ArrayList<String> names = tab == 1 ? artistNames() : albumNames(); if (i >= names.size()) return;
                drill = tab; setFilter(tab == 1 ? F_ARTIST : F_ALBUM, names.get(i));
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mode = 0; moved = false; lpFired = false; downY = y; lastY = y; pressed = -1; pressRow = -1; fl.down(e);
                    if (rBack.contains(x, y)) mode = 3;
                    else if (rMenu.contains(x, y)) mode = 4;
                    else if (rSegAll.contains(x, y)) { for (int i = 0; i < 3; i++) if (rSeg[i].contains(x, y)) pressed = i; mode = 5; }
                    else if (x >= rSeek.left - d(16) && x <= rSeek.right + d(16) && y >= rSeek.top - d(16) && y <= rSeek.bottom + d(16)) { mode = 2; seekF = Math.max(0, Math.min(1, (x - rSeek.left) / rSeek.width())); }
                    else if (rFav.contains(x, y)) mode = 7;
                    else if (rPanel.contains(x, y)) { for (int i = 0; i < 5; i++) if (rCtl[i].contains(x, y)) { pressed = i; mode = 6; } }
                    else if (rList.contains(x, y)) { mode = 1; pressRow = rowAt(y); postDelayed(lp, ViewConfiguration.getLongPressTimeout()); }
                    invalidate(); return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mode == 2) seekF = Math.max(0, Math.min(1, (x - rSeek.left) / rSeek.width()));
                    else if (mode == 1) {
                        if (Math.abs(y - downY) > d(8)) { moved = true; removeCallbacks(lp); }
                        if (moved) off -= y - lastY;
                        lastY = y;
                    }
                    else if (mode == 3 && !rBack.contains(x, y)) mode = 0;
                    else if (mode == 4 && !rMenu.contains(x, y)) mode = 0;
                    else if ((mode == 5 || mode == 6) && pressed >= 0 && !(mode == 5 ? rSeg[pressed] : rCtl[pressed]).contains(x, y)) pressed = -1;
                    fl.move(e); invalidate(); return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    removeCallbacks(lp);
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    fl.up(e, up && mode == 1 && moved, this);
                    if (up && mode == 2 && pl.loaded() && dur() > 0) seekTo((int) (seekF * dur()));
                    seekF = -1;
                    if (up && mode == 3) { if (fk != F_ALL) back(); else pickFilter(); }
                    else if (up && mode == 4) mainMenu();
                    else if (up && mode == 5 && pressed >= 0) { if (pressed != 0 && fk != F_ALL) setFilter(F_ALL, null); tab = pressed; off = 0; fl.stop(); }
                    else if (up && mode == 6 && pressed >= 0) {
                        if (pressed == 0) pl.cycleRepeat(); else if (pressed == 1) prev(); else if (pressed == 2) toggle(); else if (pressed == 3) next(true); else pl.setShuffle(!pl.shuffle);
                    }
                    else if (up && mode == 7) toggleFav(now());
                    else if (up && mode == 1 && !moved && !lpFired) { int i = rowAt(y); if (i >= 0) tapRow(i, x); }
                    pressed = -1; pressRow = -1; mode = 0; invalidate(); return true;
                }
            }
            return true;
        }
    }
}
