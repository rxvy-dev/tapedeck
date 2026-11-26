package com.rxvy.tapedeck;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finds the beats of a song by decoding it to PCM and looking for sudden jumps in (mostly bass) energy.
 * No microphone permission needed (unlike android.media.audiofx.Visualizer).
 * Decoding starts where the song is playing right now and results are delivered in chunks,
 * so the fight can start attacking within a second instead of waiting for the whole file.
 */
public final class Beats {
    public interface Sink { void got(Beats b); }

    public final long songId;
    public final int[] at;        // beat times in ms
    public final float[] power;   // 0..1, how hard each beat hits
    public final int bpm;
    public final boolean real;    // false = couldn't decode, using a steady fallback pulse
    public final boolean done;    // false = more chunks coming

    Beats(long id, int[] at, float[] power, int bpm, boolean real, boolean done) { songId = id; this.at = at; this.power = power; this.bpm = bpm; this.real = real; this.done = done; }

    /** Index of the first beat at or after t (ms). */
    public int after(int t) { int i = Arrays.binarySearch(at, t); return i >= 0 ? i : -i - 1; }

    /** Distance in ms from t to the nearest beat. */
    public int nearest(int t) {
        int i = after(t), best = Integer.MAX_VALUE;
        if (i < at.length) best = at[i] - t;
        if (i > 0) best = Math.min(best, t - at[i - 1]);
        return best;
    }

    static Beats fallback(long id, int durMs) {
        int n = Math.max(1, durMs / 500);
        int[] a = new int[n]; float[] p = new float[n];
        for (int i = 0; i < n; i++) { a[i] = 500 + i * 500; p[i] = i % 4 == 0 ? .9f : (i % 2 == 0 ? .6f : .35f); }
        return new Beats(id, a, p, 120, false, true);
    }

    static final LinkedHashMap<Long, Beats> cache = new LinkedHashMap<Long, Beats>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Beats> e) { return size() > 24; }
    };

    public static Beats cached(long id) { synchronized (cache) { return cache.get(id); } }

    // ---------- energy envelope ----------
    /** ~11.6 ms hops of bass energy (one-pole low-pass ~150 Hz) and full-band energy. */
    static final class Env {
        float[] bass = new float[4096], full = new float[4096]; int n; float msPerHop = 11.6f; int startMs;
        void add(float b, float f) {
            if (n == bass.length) { bass = Arrays.copyOf(bass, n * 2); full = Arrays.copyOf(full, n * 2); }
            bass[n] = b; full[n] = f; n++;
        }
    }

    interface Progress { boolean chunk(Env e); }   // return false to stop early

    /** Decodes [fromMs, toMs) (toMs 0 = to the end) into an envelope, calling back every ~6 s of audio. */
    static Env envelope(Context ctx, Song s, int fromMs, int toMs, Progress cb) throws Exception {
        MediaExtractor ex = new MediaExtractor(); MediaCodec dec = null; Env env = new Env();
        try {
            ex.setDataSource(ctx, Player.uri(s), null);
            int track = -1; MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i); String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return env;
            ex.selectTrack(track);
            if (fromMs > 0) ex.seekTo(fromMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            env.startMs = Math.max(0, (int) (ex.getSampleTime() / 1000));
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0); dec.start();
            int hop = Math.max(64, rate / 86); env.msPerHop = 1000f * hop / rate;
            float a = (float) (2 * Math.PI * 150 / rate), lp = 0, eb = 0, ef = 0; int k = 0;
            short[] pcm = new short[8192];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); boolean inDone = false, outDone = false;
            int idle = 0, nextCb = (int) (6000 / env.msPerHop);
            long deadline = System.currentTimeMillis() + 90000;
            while (!outDone && idle < 300 && System.currentTimeMillis() < deadline) {
                while (!inDone) {   // feed every free input buffer without waiting
                    int ii = dec.dequeueInputBuffer(0);
                    if (ii < 0) break;
                    ByteBuffer ib = dec.getInputBuffer(ii); int sz = ib == null ? -1 : ex.readSampleData(ib, 0);
                    boolean past = toMs > 0 && ex.getSampleTime() / 1000 >= toMs;
                    if (sz < 0 || past) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                    else { dec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0); ex.advance(); }
                }
                boolean got = false;
                while (true) {   // drain everything that's ready (block briefly only when nothing is)
                    int oi = dec.dequeueOutputBuffer(info, got ? 0 : 2000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) { rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); hop = Math.max(64, rate / 86); env.msPerHop = 1000f * hop / rate; a = (float) (2 * Math.PI * 150 / rate); }
                        continue;
                    }
                    if (oi < 0) break;
                    got = true;
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer(); int len = sb.remaining();
                        if (pcm.length < len) pcm = new short[len];
                        sb.get(pcm, 0, len);
                        int c1 = Math.max(1, ch); float inv = 1f / (c1 * 32768f);
                        for (int f = 0; f + c1 <= len; f += c1) {
                            float x = 0; for (int c = 0; c < c1; c++) x += pcm[f + c]; x *= inv;
                            lp += a * (x - lp); eb += lp * lp; ef += x * x;
                            if (++k >= hop) { env.add(eb / hop, ef / hop); eb = ef = 0; k = 0; }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { outDone = true; break; }
                }
                if (got) idle = 0; else if (inDone) idle++;
                if (cb != null && env.n >= nextCb) { nextCb = env.n + (int) (6000 / env.msPerHop); if (!cb.chunk(env)) break; }
            }
            return env;
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable e) { }
            try { ex.release(); } catch (Throwable e) { }
        }
    }

    // ---------- the analysis ----------
    /**
     * Delivers beats to sink, possibly several times (each delivery covers more of the song), starting near playMs.
     * Runs on the calling thread; the last delivery has done == true.
     */
    public static void analyze(Context ctx, final Song s, final int durMs, int playMs, final Sink sink) {
        Beats c = cached(s.id);
        if (c != null) { sink.got(c); return; }
        long t0 = System.currentTimeMillis();
        try {
            final int from = Math.max(0, playMs - 1500);
            // pass 1: from just before where we are, to the end, in chunks
            Env tail = envelope(ctx, s, from, 0, new Progress() { public boolean chunk(Env e) {
                Beats b = pick(s.id, e, false); if (b != null) sink.got(b); return true;
            }});
            Beats all = pick(s.id, tail, false);
            if (all == null) { sink.got(fallback(s.id, durMs)); return; }
            // pass 2: the start of the song (only needed if we began in the middle)
            if (tail.startMs > 2000) {
                sink.got(all);
                Env head = envelope(ctx, s, 0, tail.startMs + 300, null);
                Beats hb = pick(s.id, head, false);
                if (hb != null) all = merge(hb, all, tail.startMs);
            }
            all = new Beats(s.id, all.at, all.power, all.bpm, true, true);
            synchronized (cache) { cache.put(s.id, all); }
            Log.i("TapeDeck", "beats: " + s.title + " -> " + all.at.length + " beats, " + all.bpm + " bpm in " + (System.currentTimeMillis() - t0) + " ms");
            sink.got(all);
        } catch (Throwable e) {
            Log.w("TapeDeck", "beats: decode failed for " + s.title, e);
            sink.got(fallback(s.id, durMs));
        }
    }

    static Beats merge(Beats head, Beats tail, int cut) {
        int h = head.after(cut), t = tail.after(cut);
        int[] at = new int[h + tail.at.length - t]; float[] p = new float[at.length];
        System.arraycopy(head.at, 0, at, 0, h); System.arraycopy(head.power, 0, p, 0, h);
        System.arraycopy(tail.at, t, at, h, tail.at.length - t); System.arraycopy(tail.power, t, p, h, tail.at.length - t);
        return new Beats(tail.songId, at, p, tail.bpm, true, true);
    }

    /** Onset novelty (rise in log energy) + adaptive threshold + peak picking. Times are absolute (ms). */
    static Beats pick(long id, Env e, boolean done) {
        int n = e.n; float msPerHop = e.msPerHop;
        if (n < 40) return null;
        float[] nov = new float[n];
        float pb = (float) Math.log(1e-6 + e.bass[0]), pf = (float) Math.log(1e-6 + e.full[0]);
        for (int i = 1; i < n; i++) {
            float lb = (float) Math.log(1e-6 + e.bass[i]), lf = (float) Math.log(1e-6 + e.full[i]);
            nov[i] = Math.max(0, lb - pb) * 1.6f + Math.max(0, lf - pf);
            pb = lb; pf = lf;
        }
        int w = Math.max(8, (int) (700 / msPerHop)), gap = Math.max(1, (int) (190 / msPerHop)), loc = Math.max(1, (int) (60 / msPerHop));
        double[] ps = new double[n + 1], pq = new double[n + 1];
        for (int i = 0; i < n; i++) { ps[i + 1] = ps[i] + nov[i]; pq[i + 1] = pq[i] + nov[i] * nov[i]; }
        ArrayList<Integer> found = new ArrayList<>(); ArrayList<Float> pw = new ArrayList<>(); int last = -gap;
        for (int i = 1; i < n - loc; i++) {
            int l0 = Math.max(0, i - w), l1 = Math.min(n, i + w + 1), cnt = l1 - l0;
            double mean = (ps[l1] - ps[l0]) / cnt, sd = Math.sqrt(Math.max(0, (pq[l1] - pq[l0]) / cnt - mean * mean)), thr = mean + 1.1 * sd + .02;
            if (nov[i] <= thr || i - last < gap) continue;
            boolean peak = true;
            for (int j = Math.max(0, i - loc); j <= Math.min(n - 1, i + loc) && peak; j++) if (nov[j] > nov[i]) peak = false;
            if (!peak) continue;
            found.add(e.startMs + Math.round(i * msPerHop)); pw.add((float) ((nov[i] - mean) / (sd * 4 + 1e-6))); last = i;
        }
        if (found.size() < 4) return null;
        int[] at = new int[found.size()]; float[] p = new float[found.size()];
        for (int i = 0; i < at.length; i++) { at[i] = found.get(i); p[i] = Math.max(.15f, Math.min(1f, pw.get(i))); }
        return new Beats(id, at, p, tempo(nov, n, msPerHop), true, done);
    }

    /** Tempo from the autocorrelation of the onset curve (70..180 BPM, gently preferring ~120). */
    static int tempo(float[] nov, int n, float msPerHop) {
        int lo = Math.max(1, (int) (60000 / 180f / msPerHop)), hi = (int) (60000 / 70f / msPerHop), m = Math.min(n, (int) (40000 / msPerHop));
        double best = -1; int bl = Math.max(1, (int) (500 / msPerHop));
        for (int lag = lo; lag <= hi && lag < m; lag++) {
            double s = 0; for (int i = lag; i < m; i++) s += nov[i] * nov[i - lag];
            double bpm = 60000 / (lag * msPerHop), oct = Math.log(bpm / 120) / Math.log(2);
            s *= Math.exp(-oct * oct * 1.4);
            if (s > best) { best = s; bl = lag; }
        }
        return Math.round(60000 / (bl * msPerHop));
    }
}
