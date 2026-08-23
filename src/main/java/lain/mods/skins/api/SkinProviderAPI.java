package lain.mods.skins.api;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.mojang.authlib.GameProfile;
import lain.lib.SharedPool;
import lain.mods.skins.api.interfaces.IPlayerProfile;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.api.interfaces.ISkinProvider;
import lain.mods.skins.api.interfaces.ISkinProviderService;
import lain.mods.skins.impl.Shared;
import lain.mods.skins.impl.SkinLog;

public class SkinProviderAPI
{

    public static final ISkin DUMMY = new ISkin()
    {

        @Override
        public ByteBuffer getData()
        {
            return null;
        }

        @Override
        public String getSkinType()
        {
            return null;
        }

        @Override
        public boolean isDataReady()
        {
            return false;
        }

        @Override
        public void onRemoval()
        {
        }

        @Override
        public boolean setRemovalListener(Consumer<ISkin> listener)
        {
            return false;
        }

        @Override
        public boolean setSkinFilter(Function<ByteBuffer, ByteBuffer> filter)
        {
            return false;
        }

    };

    /**
     * The service for skins.
     */
    public static final ISkinProviderService SKIN = create();
    /**
     * The service for capes.
     */
    public static final ISkinProviderService CAPE = create();

    /**
     * @return an empty ISkinProviderService with default implementation. <br>
     *         a SkinBundle will be created with all available ISkin objects for that IPlayerProfile. <br>
     *         if the profile got updated during the lifetime of a SkinBundle, new ISkin objects will be gathered and a thread will be used to monitor those objects to wait their isDataReady for up to 10 seconds before updating the SkinBundle.
     */
    public static ISkinProviderService create()
    {
        return new ISkinProviderService()
        {

            private final LoadingCache<SkinBundle, AtomicReference<Object>> reloading = CacheBuilder.newBuilder().weakKeys().build(new CacheLoader<SkinBundle, AtomicReference<Object>>()
            {

                @Override
                public AtomicReference<Object> load(SkinBundle key) throws Exception
                {
                    return new AtomicReference<>();
                }

            });
            private final LoadingCache<IPlayerProfile, SkinBundle> cache = CacheBuilder.newBuilder().expireAfterAccess(15, TimeUnit.SECONDS).removalListener(new RemovalListener<IPlayerProfile, SkinBundle>()
            {

                @Override
                public void onRemoval(RemovalNotification<IPlayerProfile, SkinBundle> notification)
                {
                    SkinBundle skin = notification.getValue();
                    if (skin == null)
                        return;
                    if (notification.getCause() == RemovalCause.REPLACED)
                    {
                        // cache.refresh() stores the very same bundle back, and Guava still reports the
                        // previous value as REPLACED. Tearing it down here would throw away a perfectly
                        // good skin - and delete its GL texture - in the middle of every profile update,
                        // which is exactly when the player is on screen.
                        SkinLog.debug("keeping %s across a refresh (REPLACED)", SkinLog.id(skin));
                        return;
                    }
                    SkinLog.debug("bundle %s dropped (%s)", SkinLog.id(skin), notification.getCause());
                    skin.onRemoval();
                }

            }).build(new CacheLoader<IPlayerProfile, SkinBundle>()
            {

                @Override
                public SkinBundle load(IPlayerProfile key) throws Exception
                {
                    key.setUpdateListener(profileChangeListener);

                    SkinLog.debug("new bundle for %s (%s), %d provider(s)", key.getPlayerName(), key.getPlayerID(), providers.size());
                    SkinBundle bundle = new SkinBundle().set(providers.stream().map(provider -> {
                        return provider.getSkin(key);
                    }).filter(skin -> {
                        return skin != null;
                    }).collect(Collectors.toCollection(ArrayList::new)));
                    bundle.rememberTextures(Shared.getTextures((GameProfile) key.getOriginal()));
                    return bundle;
                }

                @Override
                public ListenableFuture<SkinBundle> reload(IPlayerProfile key, SkinBundle oldValue) throws Exception
                {
                    SkinLog.debug("reload %s requested for %s (%s)", SkinLog.id(oldValue), key.getPlayerName(), key.getPlayerID());
                    // Gather new ISkin objects.
                    Collection<ISkin> skins = providers.stream().map(provider -> {
                        return provider.getSkin(key);
                    }).filter(skin -> {
                        return skin != null;
                    }).collect(Collectors.toCollection(ArrayList::new));
                    // Prepare for monitoring.
                    Object token;
                    reloading.getUnchecked(oldValue).set(token = new Object());
                    long deadline = System.currentTimeMillis() + 10000; // 10 seconds
                    Supplier<Boolean> ready = () -> {
                        if (System.currentTimeMillis() - deadline > 0L)
                            return true;
                        if (reloading.getUnchecked(oldValue).get() != token)
                            return true;
                        boolean realReady = skins.stream().anyMatch(s -> !s.isFallback() && s.isDataReady());
                        if (realReady)
                            return true;
                        boolean pending = skins.stream().anyMatch(s -> !s.isFallback() && !s.isSettled());
                        return !pending;
                    };
                    Runnable update = () -> {
                        if (!reloading.getUnchecked(oldValue).compareAndSet(token, null))
                            return;
                        boolean newHasReal = skins.stream().anyMatch(s -> !s.isFallback() && s.isDataReady());
                        ISkin held = oldValue.resolve();
                        boolean oldHasReal = held != null && !held.isFallback() && held.isDataReady();
                        if (!newHasReal && oldHasReal)
                        {
                            // The refreshed providers came back empty (offline, rate limited, profile
                            // without textures...). Swapping now would replace a perfectly good skin
                            // with Default Steve/Alex, which is exactly the flicker we want to avoid.
                            SkinLog.debug("reload %s for %s discarded, keeping %s", SkinLog.id(oldValue), key.getPlayerName(), SkinLog.id(held));
                            skins.forEach(ISkin::onRemoval);
                            return;
                        }
                        SkinLog.debug("reload %s for %s applied, newHasReal=%s", SkinLog.id(oldValue), key.getPlayerName(), newHasReal);
                        oldValue.set(skins);
                        oldValue.rememberTextures(Shared.getTextures((GameProfile) key.getOriginal()));
                    };

                    if (skins.isEmpty())
                    {
                        update.run();
                    }
                    else
                    {
                        ManagedBlocker blocker = new ManagedBlocker()
                        {

                            @Override
                            public boolean block() throws InterruptedException
                            {
                                Thread.sleep(1000); // 1 second
                                return ready.get();
                            }

                            @Override
                            public boolean isReleasable()
                            {
                                return ready.get();
                            }

                        };
                        SharedPool.execute(() -> {
                            try
                            {
                                ForkJoinPool.managedBlock(blocker);
                            }
                            catch (InterruptedException e)
                            {
                            }
                            finally
                            {
                                update.run();
                            }
                        });
                    }
                    return Futures.immediateFuture(oldValue);
                }

            });

            private final List<ISkinProvider> providers = new CopyOnWriteArrayList<>();
            private final Consumer<IPlayerProfile> profileChangeListener = profile -> {
                SkinBundle bundle = cache.getIfPresent(profile);
                if (bundle == null)
                    return;
                // On an offline-mode server the same player is wrapped twice - once from the
                // server's offline profile, once from the resolved online one - and both wrappers
                // end up sharing this bundle. Their resolve steps would each rebuild a bundle that
                // is already built from exactly this skin.
                String textures = Shared.getTextures((GameProfile) profile.getOriginal());
                if (bundle.alreadyBuiltFrom(textures))
                {
                    SkinLog.debug("bundle %s is already built from these textures, not reloading for %s", SkinLog.id(bundle), profile.getPlayerName());
                    return;
                }
                cache.refresh(profile);
            };

            @Override
            public void clearProviders()
            {
                providers.clear();
                cache.invalidateAll();
            }

            @Override
            public ISkin getSkin(IPlayerProfile profile)
            {
                if (profile == null)
                    return DUMMY;
                return cache.getUnchecked(profile);
            }

            @Override
            public boolean registerProvider(ISkinProvider provider)
            {
                if (provider == null || provider == this)
                    return false;
                return providers.add(provider);
            }

        };
    }

}
