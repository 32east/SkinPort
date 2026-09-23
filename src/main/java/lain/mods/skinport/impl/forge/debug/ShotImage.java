package lain.mods.skinport.impl.forge.debug;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import lain.mods.skins.impl.SkinLog;

/**
 * A frame read back from an offscreen render, over a transparent background: pixels with alpha
 * are the player. Rows run top to bottom, like the screen.
 */
final class ShotImage
{

    final int width;
    final int height;
    final int[] argb;
    private long hash;
    private boolean hashed;

    ShotImage(int width, int height, int[] argb)
    {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    static ShotImage of(BufferedImage image)
    {
        int w = image.getWidth(), h = image.getHeight();
        return new ShotImage(w, h, image.getRGB(0, 0, w, h, null, 0, w));
    }

    int alpha(int x, int y)
    {
        return argb[y * width + x] >>> 24;
    }

    boolean covered(int x, int y)
    {
        return alpha(x, y) > 0;
    }

    boolean isEmpty()
    {
        for (int v : argb)
            if ((v >>> 24) != 0)
                return false;
        return true;
    }

    /**
     * Content hash; two renders of the same skin in the same pose come out bit for bit the same.
     */
    long hash()
    {
        if (!hashed)
        {
            long h = 1125899906842597L;
            for (int v : argb)
                h = 31 * h + v;
            hash = h;
            hashed = true;
        }
        return hash;
    }

    /** {minX, minY, maxX, maxY} of the covered pixels, inclusive, or null for an empty frame. */
    int[] bbox(int x0, int x1)
    {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++)
        {
            for (int x = Math.max(0, x0); x < Math.min(width, x1); x++)
            {
                if (covered(x, y))
                {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new int[] { minX, minY, maxX, maxY };
    }

    /** Covered pixels on row y between x0 (inclusive) and x1 (exclusive), outermost to outermost. */
    int span(int y, int x0, int x1)
    {
        int first = -1, last = -1;
        for (int x = Math.max(0, x0); x < Math.min(width, x1); x++)
        {
            if (covered(x, y))
            {
                if (first < 0)
                    first = x;
                last = x;
            }
        }
        return first < 0 ? 0 : last - first + 1;
    }

    /**
     * Share of pixels, among those covered in either image, that differ: coverage differs or a
     * channel is off by more than <code>tolerance</code>.
     */
    static double mismatch(ShotImage a, ShotImage b, int tolerance)
    {
        if (a.width != b.width || a.height != b.height)
            return 1.0;
        int union = 0, differ = 0;
        for (int i = 0; i < a.argb.length; i++)
        {
            int va = a.argb[i], vb = b.argb[i];
            boolean ca = (va >>> 24) > 0, cb = (vb >>> 24) > 0;
            if (!ca && !cb)
                continue;
            union++;
            if (ca != cb)
            {
                differ++;
                continue;
            }
            for (int shift = 0; shift <= 24; shift += 8)
            {
                if (Math.abs(((va >> shift) & 255) - ((vb >> shift) & 255)) > tolerance)
                {
                    differ++;
                    break;
                }
            }
        }
        return union == 0 ? 0 : differ / (double) union;
    }

    /** Same as {@link #mismatch}, only inside the rectangle [x0, x1) x [y0, y1). */
    static double mismatch(ShotImage a, ShotImage b, int x0, int y0, int x1, int y1, int tolerance)
    {
        int union = 0, differ = 0;
        for (int y = Math.max(0, y0); y < Math.min(a.height, y1); y++)
        {
            for (int x = Math.max(0, x0); x < Math.min(a.width, x1); x++)
            {
                int va = a.argb[y * a.width + x], vb = b.argb[y * b.width + x];
                boolean ca = (va >>> 24) > 0, cb = (vb >>> 24) > 0;
                if (!ca && !cb)
                    continue;
                union++;
                if (ca != cb || !close(va, vb, tolerance))
                    differ++;
            }
        }
        return union == 0 ? 0 : differ / (double) union;
    }

    private static boolean close(int va, int vb, int tolerance)
    {
        for (int shift = 0; shift <= 24; shift += 8)
            if (Math.abs(((va >> shift) & 255) - ((vb >> shift) & 255)) > tolerance)
                return false;
        return true;
    }

    BufferedImage toBufferedImage(int scale)
    {
        BufferedImage image = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height * scale; y++)
            for (int x = 0; x < width * scale; x++)
                image.setRGB(x, y, argb[(y / scale) * width + x / scale]);
        return image;
    }

    void save(File file)
    {
        try
        {
            file.getParentFile().mkdirs();
            ImageIO.write(toBufferedImage(2), "png", file);
        }
        catch (Exception e)
        {
            SkinLog.warn("[autotest] could not save %s: %s", file, e);
        }
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof ShotImage && ((ShotImage) o).width == width && ((ShotImage) o).height == height && Arrays.equals(((ShotImage) o).argb, argb);
    }

    @Override
    public int hashCode()
    {
        return (int) hash();
    }

}
