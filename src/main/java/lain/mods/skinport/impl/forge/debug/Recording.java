package lain.mods.skinport.impl.forge.debug;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import lain.mods.skins.impl.SkinLog;

/**
 * Every frame of one player from the moment it first exists: the picture that came out, and what
 * SkinPort says it drew (texture, where it came from, model). Read back once the player has
 * settled, against the frame it settled on.
 */
final class Recording
{

    static final class Sample
    {

        final int frame;
        final long ms;
        final long hash;
        final String state;
        final boolean skewed;
        final boolean empty;

        Sample(int frame, long ms, long hash, String state, boolean skewed, boolean empty)
        {
            this.frame = frame;
            this.ms = ms;
            this.hash = hash;
            this.state = state;
            this.skewed = skewed;
            this.empty = empty;
        }

    }

    /** A run of consecutive frames that looked the same and were drawn from the same state. */
    static final class Segment
    {

        final Sample first;
        Sample last;
        int frames;

        Segment(Sample first)
        {
            this.first = first;
            this.last = first;
        }

        String describe()
        {
            return String.format("%5d..%5d ms (%d frames) %s", first.ms, last.ms, frames, first.state);
        }

    }

    final String name;
    final long minMs;
    final long quietMs;
    final long maxMs;
    final List<Sample> samples = new ArrayList<>();
    final Map<Long, ShotImage> images = new LinkedHashMap<>();
    private long startNanos = -1L;
    private long lastChangeMs;
    private String lastKey;
    private int frame;

    Recording(String name, long minMs, long quietMs, long maxMs)
    {
        this.name = name;
        this.minMs = minMs;
        this.quietMs = quietMs;
        this.maxMs = maxMs;
    }

    void add(ShotImage image, String state, boolean skewed)
    {
        long now = System.nanoTime();
        if (startNanos < 0L)
            startNanos = now;
        long ms = (now - startNanos) / 1000000L;
        long hash = image.hash();
        if (!images.containsKey(hash))
            images.put(hash, image);
        Sample sample = new Sample(frame++, ms, hash, state, skewed, image.isEmpty());
        samples.add(sample);
        String key = hash + "|" + state;
        if (!key.equals(lastKey))
        {
            lastKey = key;
            lastChangeMs = ms;
        }
    }

    boolean started()
    {
        return startNanos >= 0L;
    }

    long elapsedMs()
    {
        return startNanos < 0L ? 0L : (System.nanoTime() - startNanos) / 1000000L;
    }

    /** Settled: long enough, and nothing has changed for a while; or out of time. */
    boolean done()
    {
        long elapsed = elapsedMs();
        if (!started())
            return false;
        return elapsed >= maxMs || (elapsed >= minMs && elapsed - lastChangeMs >= quietMs);
    }

    boolean timedOut()
    {
        return elapsedMs() >= maxMs && elapsedMs() - lastChangeMs < quietMs;
    }

    Sample last()
    {
        return samples.isEmpty() ? null : samples.get(samples.size() - 1);
    }

    ShotImage lastImage()
    {
        Sample last = last();
        return last == null ? null : images.get(last.hash);
    }

    List<Segment> segments()
    {
        List<Segment> segments = new ArrayList<>();
        Segment current = null;
        for (Sample s : samples)
        {
            if (current == null || current.first.hash != s.hash || !current.first.state.equals(s.state))
                segments.add(current = new Segment(s));
            current.last = s;
            current.frames++;
        }
        return segments;
    }

    /**
     * Writes one picture per distinct thing that was drawn, in order, each labelled with when it
     * was on screen: a strip that shows at a glance what a player saw while the skin loaded.
     */
    void saveStrip(File file, long goodHash)
    {
        List<Segment> segments = segments();
        // Several segments can share a picture (the same image drawn from two states, or the
        // player coming back to a picture); the strip shows every segment, capped to stay readable
        int shown = Math.min(segments.size(), 12);
        int cellW = PlayerShot.WIDTH, cellH = PlayerShot.HEIGHT + 34;
        BufferedImage strip = new BufferedImage(cellW * Math.max(1, shown), cellH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = strip.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(40, 40, 48));
        g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (int i = 0; i < shown; i++)
        {
            Segment segment = segments.get(i);
            ShotImage image = images.get(segment.first.hash);
            int x0 = i * cellW;
            boolean good = segment.first.hash == goodHash;
            g.setColor(segment.first.empty ? new Color(70, 70, 70) : good ? new Color(46, 92, 60) : new Color(110, 40, 40));
            g.fillRect(x0, 0, cellW, PlayerShot.HEIGHT);
            if (image != null)
                g.drawImage(image.toBufferedImage(1), x0, 0, null);
            g.setColor(Color.WHITE);
            g.drawString(String.format("%d-%d ms, %d fr", segment.first.ms, segment.last.ms, segment.frames), x0 + 4, PlayerShot.HEIGHT + 13);
            String state = segment.first.state;
            g.drawString(state.length() > 40 ? state.substring(0, 40) : state, x0 + 4, PlayerShot.HEIGHT + 27);
            g.setColor(new Color(20, 20, 24));
            g.drawLine(x0 + cellW - 1, 0, x0 + cellW - 1, cellH);
        }
        g.dispose();
        try
        {
            file.getParentFile().mkdirs();
            ImageIO.write(strip, "png", file);
        }
        catch (Exception e)
        {
            SkinLog.warn("[autotest] could not save %s: %s", file, e);
        }
    }

}
