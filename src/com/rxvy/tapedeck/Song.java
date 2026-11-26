package com.rxvy.tapedeck;

/** One track from the phone's MediaStore. */
public class Song {
    public long id, albumId, added;
    public String title, artist, album, file;
    public int dur, track;

    // Same track = same MediaStore id (lets the queue survive a library refresh).
    @Override public boolean equals(Object o) { return o instanceof Song && ((Song) o).id == id; }
    @Override public int hashCode() { return (int) (id ^ (id >>> 32)); }
}
