package lain.mods.skins.api;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.api.interfaces.ISkin.Loaded;
import lain.mods.skins.impl.SkinLog;

/**
 * A special ISkin object that will return first ready ISkin object in a collection. <br>
 * It supports swapping it's collection reference at runtime.
 *
 */
public class SkinBundle implements ISkin
{

    protected final AtomicReference<Collection<ISkin>> ref = new AtomicReference<>(Collections.emptyList());
    protected final Collection<Consumer<ISkin>> listeners = new CopyOnWriteArrayList<>();
    protected final Collection<Function<ByteBuffer, ByteBuffer>> filters = new CopyOnWriteArrayList<>();
    /**
     * The ISkin currently on screen. Kept sticky so that a provider finishing later does not
     * yank the texture (and with it the model) out from under a skin that already renders fine.
     * A default Steve/Alex standing in is never sticky: it gives way to the first real skin.
     */
    private volatile ISkin winner;
    /**
     * Non-null while a reload is gathering new skins for this bundle: the token of the latest one.
     * Owned by the service that reloads it.
     */
    public final AtomicReference<Object> reloadToken = new AtomicReference<>();
    /** The textures blob this bundle's providers were pointed at, once it is known. */
    private volatile String textures;
    private volatile boolean texturesKnown;

    /**
     * @return true when a profile update carrying <code>candidate</code> would only rebuild this
     *         bundle from the very same skin - or from no skin at all while we already hold one.
     */
    public boolean alreadyBuiltFrom(String candidate)
    {
        if (!texturesKnown)
            return false;
        if (candidate == null)
            return textures != null;
        return candidate.equals(textures);
    }

    public void rememberTextures(String value)
    {
        textures = value;
        texturesKnown = true;
    }

    protected Optional<ISkin> find()
    {
        return Optional.ofNullable(resolve());
    }

    /**
     * @return the single ISkin this bundle currently stands for: the player's own skin once a
     *         provider has it, until then the default Steve/Alex for this player (null only when
     *         there is not even that). Callers that need both the image and the model type must
     *         read them off this one object - reading them through separate
     *         getData()/getSkinType() calls can straddle a provider completing and mix a texture
     *         with the wrong model.
     */
    public ISkin resolve()
    {
        Collection<ISkin> skins;
        if ((skins = ref.get()).isEmpty())
            return null;

        ISkin held = winner;
        if (held != null && !held.isFallback() && held.isDataReady() && contains(skins, held))
            return held;

        ISkin firstReady = null;
        ISkin fallback = null;
        for (ISkin s : skins)
        {
            if (s.isFallback())
            {
                if (s.isDataReady() && fallback == null)
                    fallback = s;
                continue;
            }
            if (s.isDataReady() && firstReady == null)
                firstReady = s;
        }

        // While the real skin is still on its way the default Steve/Alex stands in for it. The
        // alternative - nothing from SkinPort, so vanilla's texture - is worse on every count:
        // vanilla's textures are 64x32, and drawn on this model they come out mangled (the face
        // lands on the chest); and it is a Steve all the same, just a broken one.
        ISkin picked = firstReady != null ? firstReady : fallback;

        if (picked != held)
        {
            winner = picked;
            SkinLog.debug("bundle %s winner %s -> %s (type=%s)", SkinLog.id(this), SkinLog.id(held), SkinLog.id(picked), picked == null ? "-" : picked.getSkinType());
        }
        return picked;
    }

    /**
     * @return true while this bundle may still come up with a real skin: a provider has not
     *         finished yet, or a reload is gathering new ones.
     */
    public boolean isPending()
    {
        if (reloadToken.get() != null)
            return true;
        for (ISkin s : ref.get())
            if (!s.isFallback() && !s.isDataReady() && !s.isSettled())
                return true;
        return false;
    }

    private static boolean contains(Collection<ISkin> skins, ISkin skin)
    {
        for (ISkin s : skins)
            if (s == skin)
                return true;
        return false;
    }

    @Override
    public ByteBuffer getData()
    {
        return find().orElse(SkinProviderAPI.DUMMY).getData();
    }

    @Override
    public String getSkinType()
    {
        return find().orElse(SkinProviderAPI.DUMMY).getSkinType();
    }

    @Override
    public boolean isDataReady()
    {
        return find().orElse(SkinProviderAPI.DUMMY).isDataReady();
    }

    @Override
    public Loaded loaded()
    {
        ISkin skin = resolve();
        return skin == null ? null : skin.loaded();
    }

    @Override
    public void onRemoval()
    {
        set(Collections.emptyList());
    }

    public SkinBundle set(Collection<ISkin> c)
    {
        Objects.requireNonNull(c);
        winner = null;
        if (!c.isEmpty())
        {
            for (ISkin e : c)
            {
                listeners.forEach(e::setRemovalListener);
                filters.forEach(e::setSkinFilter);
            }
        }
        Collection<ISkin> skins;
        if (!(skins = ref.getAndSet(c)).isEmpty())
            skins.forEach(ISkin::onRemoval);
        return this;
    }

    @Override
    public boolean setRemovalListener(Consumer<ISkin> listener)
    {
        if (listener == null || listeners.contains(listener))
            return false;
        if (listeners.add(listener))
        {
            Collection<ISkin> skins;
            if (!(skins = ref.get()).isEmpty())
                skins.forEach(e -> e.setRemovalListener(listener));
            return true;
        }
        return false;
    }

    @Override
    public boolean setSkinFilter(Function<ByteBuffer, ByteBuffer> filter)
    {
        if (filter == null || filters.contains(filter))
            return false;
        if (filters.add(filter))
        {
            Collection<ISkin> skins;
            if (!(skins = ref.get()).isEmpty())
                skins.forEach(e -> e.setSkinFilter(filter));
            return true;
        }
        return false;
    }

}
