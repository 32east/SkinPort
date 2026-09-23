package lain.mods.skins.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.imageio.ImageIO;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.api.interfaces.ISkin.Loaded;

public class SkinData implements ISkin
{

    public static String judgeSkinType(byte[] data)
    {
        try (InputStream input = new ByteArrayInputStream(data))
        {
            return judgeSkinType(ImageIO.read(input));
        }
        catch (Throwable t)
        {
            return "unknown";
        }
    }

    public static String judgeSkinType(ByteBuffer data)
    {
        try (InputStream input = wrapByteBufferAsInputStream(data))
        {
            return judgeSkinType(ImageIO.read(input));
        }
        catch (Throwable t)
        {
            return "unknown";
        }
    }

    public static String judgeSkinType(BufferedImage image)
    {
        if (image == null)
            return "unknown";
        int w = image.getWidth();
        int h = image.getHeight();
        if (w == h * 2)
            return "default"; // 64x32 legacy; LegacyConversion turns these into default.
        if (w != h)
            return "unknown";

        // Slim (Alex) arms are 3px. The 4th column of Steve's right-arm back (x=54..55,
        // y=20..31 on a 64x64 sheet) is unused on slim skins. Checking only (55,20) for
        // *fully* transparent pixels mis-detects most modern slim skins as Steve.
        int r = Math.max(w / 64, 1);
        int opaque = 0;
        int total = 0;
        for (int y = 20 * r; y < 32 * r && y < h; y++)
        {
            for (int x = 54 * r; x < 56 * r && x < w; x++)
            {
                total++;
                if (((image.getRGB(x, y) >> 24) & 0xFF) >= 128)
                    opaque++;
            }
        }
        if (total == 0)
            return "default";
        return (opaque * 4 < total) ? "slim" : "default";
    }

    public static String judgeSkinType(byte[] data, String modelHint)
    {
        String normalized = normalizeModelHint(modelHint);
        if (normalized != null)
            return normalized;
        return judgeSkinType(data);
    }

    public static String normalizeModelHint(String hint)
    {
        if (hint == null || hint.isEmpty())
            return null;
        String t = hint.toLowerCase(Locale.ROOT);
        if ("slim".equals(t) || "alex".equals(t))
            return "slim";
        if ("default".equals(t) || "steve".equals(t) || "classic".equals(t) || "wide".equals(t))
            return "default";
        return null;
    }

    public static ByteBuffer toBuffer(byte[] data)
    {
        ByteBuffer buf = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        buf.put(data);
        // Cast to Buffer so this compiles on JDK 9+ but still calls Buffer.rewind() on Java 8.
        ((Buffer) buf).rewind();
        return buf;
    }

    public static boolean validateData(byte[] data)
    {
        try (InputStream input = new ByteArrayInputStream(data))
        {
            return ImageIO.read(input) != null;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    public static InputStream wrapByteBufferAsInputStream(ByteBuffer original)
    {
        ByteBuffer buf = original.duplicate();
        return new InputStream()
        {

            @Override
            public int read() throws IOException
            {
                if (!buf.hasRemaining())
                    return -1;
                return buf.get() & 0xFF;
            }

            @Override
            public int read(byte[] bytes, int off, int len) throws IOException
            {
                if (!buf.hasRemaining())
                    return -1;
                len = Math.min(len, buf.remaining());
                buf.get(bytes, off, len);
                return len;
            }

        };
    }

    // One volatile reference instead of two plain fields: provider threads write while the client
    // thread renders, and a torn read pairs the new image with the old (or a null) model type.
    private volatile Loaded loaded;
    private volatile boolean settled;
    /** Which provider fills this one ("mojang", "crafatar", "default-steve"...), for logs and the autotest. */
    private final String origin;
    private volatile boolean fallback;
    private volatile boolean shared;
    private final Collection<Consumer<ISkin>> listeners = new CopyOnWriteArrayList<>();
    private final Collection<Function<ByteBuffer, ByteBuffer>> filters = new CopyOnWriteArrayList<>();

    public SkinData()
    {
        this("unknown");
    }

    public SkinData(String origin)
    {
        this.origin = origin;
    }

    /**
     * @return the provider this came from, and what it holds right now.
     */
    public String describe()
    {
        Loaded l = loaded;
        return origin + (fallback ? " (fallback)" : "") + (l != null ? " " + l.type : settled ? " (nothing)" : " (pending)");
    }

    public String getOrigin()
    {
        return origin;
    }

    @Override
    public ByteBuffer getData()
    {
        Loaded l = loaded;
        return l == null ? null : l.data;
    }

    @Override
    public String getSkinType()
    {
        Loaded l = loaded;
        return l == null ? null : l.type;
    }

    @Override
    public Loaded loaded()
    {
        return loaded;
    }

    @Override
    public boolean isDataReady()
    {
        return loaded != null;
    }

    @Override
    public boolean isSettled()
    {
        return settled || loaded != null;
    }

    @Override
    public boolean isFallback()
    {
        return fallback;
    }

    public void markSettled()
    {
        settled = true;
    }

    public SkinData asFallback()
    {
        fallback = true;
        settled = true;
        // Default Steve/Alex is one instance handed to every player's bundle. Whichever bundle is
        // dropped first must not null it out for everyone else, and must not delete the single GL
        // texture they all share - so this instance ignores removal entirely.
        shared = true;
        return this;
    }

    @Override
    public synchronized void onRemoval()
    {
        if (shared)
            return;

        // Notify first: the listeners identify their texture by getData(), so the buffer has to
        // still be reachable when they run.
        for (Consumer<ISkin> listener : listeners)
            listener.accept(this);

        loaded = null;
    }

    public synchronized void put(byte[] data, String type)
    {
        ByteBuffer buf = null;
        if (data != null)
        {
            buf = toBuffer(data);
            for (Function<ByteBuffer, ByteBuffer> filter : filters)
                if ((buf = filter.apply(buf)) == null)
                    break;
        }

        this.loaded = buf == null ? null : new Loaded(buf, type);
        this.settled = true;
    }

    @Override
    public boolean setRemovalListener(Consumer<ISkin> listener)
    {
        // A shared instance is never removed, so registering here would only grow the list forever.
        if (shared || listener == null || listeners.contains(listener))
            return false;
        return listeners.add(listener);
    }

    @Override
    public boolean setSkinFilter(Function<ByteBuffer, ByteBuffer> filter)
    {
        if (filter == null || filters.contains(filter))
            return false;
        return filters.add(filter);
    }

}
