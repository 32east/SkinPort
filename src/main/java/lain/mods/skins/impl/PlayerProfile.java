package lain.mods.skins.impl;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.mojang.authlib.GameProfile;
import lain.mods.skins.api.interfaces.IPlayerProfile;

public class PlayerProfile implements IPlayerProfile
{

    private static final PlayerProfile DUMMY = new PlayerProfile(Shared.DUMMY);

    private static final LoadingCache<GameProfile, PlayerProfile> profiles = CacheBuilder.newBuilder().weakKeys().refreshAfterWrite(10, TimeUnit.MINUTES).build(new CacheLoader<GameProfile, PlayerProfile>()
    {

        @Override
        public PlayerProfile load(GameProfile key) throws Exception
        {
            if (key.getProperties() == null || key == Shared.DUMMY) // bad profile
                return DUMMY;

            PlayerProfile profile = new PlayerProfile(key);
            if (Shared.isBlank(key.getName())) // an incomplete profile that needs filling
            {
                if (key.getId() != null) // requires an ID to fill it
                {
                    Futures.addCallback(MojangService.fillProfile(key), new FutureCallback<GameProfile>() // fill it
                    {

                        @Override
                        public void onFailure(Throwable t)
                        {
                        }

                        @Override
                        public void onSuccess(GameProfile filled)
                        {
                            if (filled == key) // failed
                                return;
                            profile.set(filled);
                        }

                    }, Runnable::run);
                }
            }
            else if (Shared.isOfflinePlayer(key.getId(), key.getName())) // an offline profile that needs resolving
            {
                Futures.addCallback(MojangService.getProfile(key.getName()), new FutureCallback<GameProfile>() // resolve it
                {

                    @Override
                    public void onFailure(Throwable t)
                    {
                    }

                    @Override
                    public void onSuccess(GameProfile resolved)
                    {
                        if (resolved == Shared.DUMMY) // failed
                            return;
                        profile.set(resolved);

                        Futures.addCallback(MojangService.fillProfile(resolved), new FutureCallback<GameProfile>() // fill it
                        {

                            @Override
                            public void onFailure(Throwable t)
                            {
                            }

                            @Override
                            public void onSuccess(GameProfile filled)
                            {
                                if (filled == resolved) // failed or already filled
                                    return;
                                profile.set(filled);
                            }

                        }, Runnable::run);
                    }

                }, Runnable::run);
            }
            else if (key.getProperties().isEmpty()) // an assumed online profile that needs filling
            {
                Futures.addCallback(MojangService.fillProfile(key), new FutureCallback<GameProfile>() // fill it
                {

                    @Override
                    public void onFailure(Throwable t)
                    {
                    }

                    @Override
                    public void onSuccess(GameProfile filled)
                    {
                        if (filled != key) // success
                        {
                            profile.set(filled);
                            return;
                        }
                        // failed, possible offline profile with bad ID
                        Futures.addCallback(MojangService.getProfile(key.getName()), new FutureCallback<GameProfile>() // resolve it
                        {

                            @Override
                            public void onFailure(Throwable t)
                            {
                            }

                            @Override
                            public void onSuccess(GameProfile resolved)
                            {
                                if (resolved == Shared.DUMMY) // failed
                                    return;
                                profile.set(resolved);

                                Futures.addCallback(MojangService.fillProfile(resolved), new FutureCallback<GameProfile>() // fill it
                                {

                                    @Override
                                    public void onFailure(Throwable t)
                                    {
                                    }

                                    @Override
                                    public void onSuccess(GameProfile filled)
                                    {
                                        if (filled == resolved) // failed or already filled
                                            return;
                                        profile.set(filled);
                                    }

                                }, Runnable::run);
                            }

                        }, Runnable::run);
                    }

                }, Runnable::run);
            }

            return profile;
        }

        @Override
        public ListenableFuture<PlayerProfile> reload(GameProfile key, PlayerProfile oldValue) throws Exception
        {
            if (oldValue == DUMMY) // value for bad profile
                return Futures.immediateFuture(DUMMY);
            return Shared.submitTask(() -> {
                PlayerProfile newValue = load(key);
                if (oldValue.getOriginal() != newValue.getOriginal()) // updated
                    oldValue.set(newValue.getOriginal()); // update old profile
                return newValue;
            });
        }

    });

    /**
     * @param profile the profile to wrap.
     * @return a PlayerProfile with a GameProfile wrapped in it, this profile will receive updates later if applicable.
     */
    public static PlayerProfile wrapGameProfile(GameProfile profile)
    {
        if (profile == null)
            return DUMMY;
        return profiles.getUnchecked(profile);
    }

    private WeakReference<GameProfile> _profile;
    private final Collection<Consumer<IPlayerProfile>> _listeners = new CopyOnWriteArrayList<>();
    /**
     * The id and name the player was first seen with. Equality and the hash stay on these: the
     * wrapped profile moves on as it gets resolved and filled - an offline id is swapped for the
     * online one - and a key whose hash moves strands its entry in a hash-based cache. The next
     * lookup misses, builds a second skin bundle from scratch and downloads everything again,
     * while the bundle that is actually being updated can no longer be found.
     */
    private final UUID _originalId;
    private final String _originalName;

    // Package-private for the unit tests; everything else goes through wrapGameProfile().
    PlayerProfile(GameProfile profile)
    {
        if (profile == null)
            throw new IllegalArgumentException("profile must not be null");
        _profile = new WeakReference<GameProfile>(profile);
        _originalId = profile.getId();
        _originalName = profile.getName();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (!(o instanceof PlayerProfile))
            return false;
        PlayerProfile p = (PlayerProfile) o;
        return Objects.equals(_originalId, p._originalId) && Objects.equals(_originalName, p._originalName);
    }

    /**
     * @return the id the player was first seen with - what the server calls them - however the
     *         profile has been resolved since.
     */
    public UUID getOriginalID()
    {
        return _originalId;
    }

    @Override
    public GameProfile getOriginal()
    {
        GameProfile p;
        if ((p = _profile.get()) == null) // gc
            return Shared.DUMMY;
        return p;
    }

    @Override
    public UUID getPlayerID()
    {
        GameProfile p;
        if ((p = _profile.get()) == null) // gc
            return Shared.DUMMY.getId();
        return p.getId();
    }

    @Override
    public String getPlayerName()
    {
        GameProfile p;
        if ((p = _profile.get()) == null) // gc
            return Shared.DUMMY.getName();
        return p.getName();
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(_originalId, _originalName);
    }

    synchronized void set(GameProfile profile)
    {
        if (this == DUMMY)
            return;
        if (profile == null)
            throw new IllegalArgumentException("profile must not be null");

        // Resolving a player runs in several steps (look up by name, then fill), and each step used
        // to fire a listener and start a full reload - four downloads of the same image in a second.
        // Only an update that actually changes the skin is worth reloading for.
        GameProfile current = _profile.get();
        if (current != null && Objects.equals(current.getId(), profile.getId()))
        {
            String currentTextures = Shared.getTextures(current);
            String newTextures = Shared.getTextures(profile);
            if (newTextures == null && currentTextures != null)
            {
                SkinLog.debug("profile step for %s carries no textures, keeping the ones we have", profile.getName());
                return;
            }
            if (Objects.equals(currentTextures, newTextures))
            {
                _profile = new WeakReference<GameProfile>(profile);
                SkinLog.debug("profile step for %s describes the same skin, not reloading", profile.getName());
                return;
            }
        }

        _profile = new WeakReference<GameProfile>(profile);
        SkinLog.debug("profile updated: %s (%s) properties=%d model=%s", profile.getName(), profile.getId(), profile.getProperties() == null ? 0 : profile.getProperties().size(), Shared.getModelHint(profile));

        for (Consumer<IPlayerProfile> l : _listeners)
            l.accept(this);
    }

    @Override
    public boolean setUpdateListener(Consumer<IPlayerProfile> listener)
    {
        if (this == DUMMY)
            return false;
        if (listener == null || _listeners.contains(listener))
            return false;
        return _listeners.add(listener);
    }

}
