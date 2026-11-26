package com.rxvy.tapedeck;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Finds the beats of a song by decoding it to PCM and looking for sudden jumps in (mostly bass) energy.
 * No microphone permission needed (unlike android.media.audiofx.Visualizer).
 */
public final class Beats {
    public final long songId;
    public final int[] at;        // beat times in ms
    public final float[] power;   // 0..1, how hard each beat hits
    public final int bpm;
    public final boolean real;    // false = couldn't decode, using a steady fallback pulse

    Beats(long id, int[] at, float[] power, int bpm, boolean real) { songId = id; this.at = at; this.power = power; this.bpm = bpm; this.real = real; }

    /** Index of the first beat at or after t (ms). */
    public int after(int t) {
        int i = Arrays.binarySearch(at, t);
        return i >= 0 ? i : -i - 1;
    }

    /** Distance in ms from t to the nearest beat. */
    public int nearest(int t) {
        int i = after(t), best = Integer.MAX_VALUE;
        if (i < at.length) best = at[i] - t;
        if (i > 0) best = Math.min(best, t - at[i - 1]);
        return best;
    }

    static Beats fallback(Song s, int durMs) {
        int bpm = 120, n = Math.max(1, durMs / 500);
        int[] a = new int[n]; float[] p = new float[n];
        for (int i = 0; i < n; i++) { a[i] = 500 + i * 500; p[i] = i % 4 == 0 ? .9f : (i % 2 == 0 ? .6f : .35f); }
        return new Beats(s.id, a, p, bpm, false);
    }

    public static Beats analyze(Context ctx, Song s, int durMs) {
        MediaExtractor ex = new MediaExtractor(); MediaCodec dec = null;
        try {
            ex.setDataSource(ctx, Player.uri(s), null);
            int track = -1; MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i); String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return fallback(s, durMs);
            ex.selectTrack(track);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0); dec.start();

            // energy envelope: one value per ~11.6 ms hop, bass (one-pole low-pass ~150 Hz) and full band
            int hop = Math.max(64, rate / 86);
            float a = (float) (2 * Math.PI * 150 / rate), lp = 0, eb = 0, ef = 0; int k = 0;
            float[] bass = new float[Math.max(1024, (int) ((long) Math.max(durMs, 1000) * 86 / 1000) + 2048)], full = new float[bass.length]; int n = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); boolean inDone = false, outDone = false; int idle = 0;
            while (!outDone && idle < 200) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii); int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 5000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) { rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); hop = Math.max(64, rate / 86); a = (float) (2 * Math.PI * 150 / rate); }
                } else if (oi >= 0) {
                    idle = 0;
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer(); int frames = sb.remaining() / Math.max(1, ch);
                        for (int f = 0; f < frames; f++) {
                            float x = 0; for (int c = 0; c < ch; c++) x += sb.get(); x /= (ch * 32768f);
                            lp += a * (x - lp); eb += lp * lp; ef += x * x;
                            if (++k >= hop) {
                                if (n == bass.length) { bass = Arrays.copyOf(bass, n * 2); full = Arrays.copyOf(full, n * 2); }
                                bass[n] = eb / hop; full[n] = ef / hop; n++; eb = ef = 0; k = 0;
                            }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (inDone) idle++;
            }
            if (n < 100) return fallback(s, durMs);
            return pick(s.id, bass, full, n, 1000f * hop / rate);
        } catch (Throwable e) {
            return fallback(s, durMs);
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable e) { }
            try { ex.release(); } catch (Throwable e) { }
        }
    }

    /** Onset novelty (rise in log energy) + adaptive threshold + peak picking. */
    static Beats pick(long id, float[] bass, float[] full, int n, float msPerHop) {
        float[] nov = new float[n];
        float pb = (float) Math.log(1e-6 + bass[0]), pf = (float) Math.log(1e-6 + full[0]);
        for (int i = 1; i < n; i++) {
            float lb = (float) Math.log(1e-6 + bass[i]), lf = (float) Math.log(1e-6 + full[i]);
            nov[i] = Math.max(0, lb - pb) * 1.6f + Math.max(0, lf - pf);
            pb = lb; pf = lf;
        }
        int w = Math.max(8, (int) (700 / msPerHop)), gap = Math.max(1, (int) (190 / msPerHop)), loc = Math.max(1, (int) (60 / msPerHop));
        // mean / std over a ~1.4 s window around each hop (prefix sums)
        double[] ps = new double[n + 1], pq = new double[n + 1];
        for (int i = 0; i < n; i++) { ps[i + 1] = ps[i] + nov[i]; pq[i + 1] = pq[i] + nov[i] * nov[i]; }
        ArrayList<Integer> found = new ArrayList<>(); ArrayList<Float> pw = new ArrayList<>(); int last = -gap;
        for (int i = 1; i < n; i++) {
            int l0 = Math.max(0, i - w), l1 = Math.min(n, i + w + 1), cnt = l1 - l0;
            double mean = (ps[l1] - ps[l0]) / cnt, sd = Math.sqrt(Math.max(0, (pq[l1] - pq[l0]) / cnt - mean * mean)), thr = mean + 1.1 * sd + .02;
            if (nov[i] <= thr || i - last < gap) continue;
            boolean peak = true;
            for (int j = Math.max(0, i - loc); j <= Math.min(n - 1, i + loc) && peak; j++) if (nov[j] > nov[i]) peak = false;
            if (!peak) continue;
            found.add(Math.round(i * msPerHop)); pw.add((float) ((nov[i] - mean) / (sd * 4 + 1e-6))); last = i;
        }
        if (found.size() < 8) return fallbackFrom(id, n, msPerHop);
        int[] at = new int[found.size()]; float[] p = new float[found.size()];
        for (int i = 0; i < at.length; i++) { at[i] = found.get(i); p[i] = Math.max(.15f, Math.min(1f, pw.get(i))); }
        // tempo: median gap between beats, folded into 70..180 BPM
        int[] gaps = new int[at.length - 1]; for (int i = 1; i < at.length; i++) gaps[i - 1] = at[i] - at[i - 1];
        Arrays.sort(gaps); float g = Math.max(150, gaps[gaps.length / 2]); float bpm = 60000f / g;
        while (bpm > 180) bpm /= 2; while (bpm < 70) bpm *= 2;
        return new Beats(id, at, p, Math.round(bpm), true);
    }

    static Beats fallbackFrom(long id, int n, float msPerHop) {
        Song s = new Song(); s.id = id; return fallback(s, Math.round(n * msPerHop));
    }
}
