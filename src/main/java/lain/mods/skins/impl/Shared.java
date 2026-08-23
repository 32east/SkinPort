package lain.mods.skins.impl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.properties.Property;
import lain.lib.Retries;
import lain.lib.SharedPool;
import lain.lib.SimpleDownloader;
import lain.mods.skins.impl.forge.MinecraftUtils;

public class Shared
{

    private static interface SupplierBlocker<T> extends Supplier<T>, ForkJoinPool.ManagedBlocker
    {
    }

    public static final GameProfile DUMMY = new GameProfile(UUID.fromString("ae9460f5-bf72-468e-89b6-4eead59001ad"), "");

    private static final Cache<UUID, Boolean> offlines = CacheBuilder.newBuilder().weakKeys().build();

    /**
     * Call a possibly blocking task in a ManagedBlocker to allow current Thread adjust if it is a ForkJoinWorkerThread.
     *
     * @param callable     the task to call.
     * @param defaultValue a default value to return if the task failed during the call.
     * @param consumer     a consumer that will receive a Throwable if the task failed during the call, null is acceptable.
     * @return the result of the task or defaultValue if it failed during the call.
     */
    public static <T> T blockyCall(Callable<T> callable, T defaultValue, Consumer<Throwable> consumer)
    {
        if (callable == null)
            return defaultValue;
        return new SupplierBlocker<T>()
        {

            T result;

            @Override
            public boolean block() throws InterruptedException
            {
                try
                {
                    result = callable.call();
                }
                catch (Throwable t)
                {
                    if (consumer != null)
                        consumer.accept(t);
                    result = defaultValue;
                }
                return true;
            }

            @Override
            public T get()
            {
                try
                {
                    ForkJoinPool.managedBlock(this);
                }
                catch (InterruptedException e)
                {
                    if (consumer != null)
                        consumer.accept(e);
                }
                return result;
            }

            @Override
            public boolean isReleasable()
            {
                return false;
            }

        }.get();
    }

    /**
     * Completely read a file in a ManagedBlocker to allow current Thread adjust if it is a ForkJoinWorkerThread.
     *
     * @param file            the file to read.
     * @param defaultContents a default value to return if failed during reading the file.
     * @param consumer        a consumer that will receive a Throwable if failed during reading the file, null is acceptable.
     * @return the contents of the file or defaultContents if failed during reading the file.
     */
    public static byte[] blockyReadFile(File file, byte[] defaultContents, Consumer<Throwable> consumer)
    {
        if (file == null)
            return defaultContents;
        return blockyCall(() -> {
            try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ); ByteArrayOutputStream baos = new ByteArrayOutputStream())
            {
                channel.transferTo(0L, Long.MAX_VALUE, Channels.newChannel(baos));
                return baos.toByteArray();
            }
        }, defaultContents, consumer);
    }

    public static CompletableFuture<Optional<byte[]>> downloadSkin(String resource, Executor executor)
    {
        return SimpleDownloader
                .start(encodeURL(resource), null, MinecraftUtils.getProxy(), 2, null, executor, null, Shared::preConnect, Shared::stopIfHttpClientError)
                .thenApply(Shared::readAndDelete);
    }

    private static String encodeURL(String url)
    {
        try
        {
            return new URI(url).toASCIIString();
        }
        catch (NullPointerException | URISyntaxException e)
        {
            return url;
        }
    }

    /**
     * The raw textures blob a profile carries, or null when it carries none. Two profiles with the
     * same blob describe the same skin, however many resolve/fill steps it took to get there.
     */
    public static String getTextures(GameProfile profile)
    {
        if (profile == null || profile.getProperties() == null)
            return null;
        Collection<Property> properties = profile.getProperties().get("textures");
        if (properties == null || properties.isEmpty())
            return null;
        return properties.iterator().next().getValue();
    }

    /**
     * The authoritative model ("slim" / "default") published by Mojang in the profile's texture
     * metadata. This is what vanilla itself uses, and it is available as soon as the profile has
     * been filled - long before any skin image finishes downloading. Purely local: it only decodes
     * a property that is already attached to the profile, no network access.
     *
     * @return "slim", "default", or null when the profile carries no texture metadata yet.
     */
    public static String getModelHint(GameProfile profile)
    {
        if (profile == null || profile.getProperties() == null || profile.getProperties().isEmpty())
            return null;
        try
        {
            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = MinecraftUtils.getSessionService().getTextures(profile, false);
            if (textures == null)
                return null;
            MinecraftProfileTexture skin = textures.get(MinecraftProfileTexture.Type.SKIN);
            if (skin == null)
                return null;
            return SkinData.normalizeModelHint(skin.getMetadata("model"));
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    public static boolean isBlank(CharSequence cs)
    {
        int strLen;
        if (cs == null || (strLen = cs.length()) == 0)
            return true;
        for (int i = 0; i < strLen; i++)
            if (!Character.isWhitespace(cs.charAt(i)))
                return false;
        return true;
    }

    public static boolean isOfflinePlayer(UUID id, String name)
    {
        if (id == null || isBlank(name)) // treat incomplete profiles as offline profiles, but don't cache results for them as they can be updated later and possibly become online profiles.
            return true;
        try
        {
            return offlines.get(id, () -> {
                return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).equals(id);
            });
        }
        catch (Throwable t)
        {
            return true;
        }
    }

    private static void preConnect(URLConnection conn)
    {
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setUseCaches(true);
        conn.setDoInput(true);
        conn.setDoOutput(false);
    }

    private static Optional<byte[]> readAndDelete(Optional<Path> path)
    {
        try (FileChannel channel = FileChannel.open(path.orElseThrow(FileNotFoundException::new), StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE); ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            channel.transferTo(0L, Long.MAX_VALUE, Channels.newChannel(baos));
            return Optional.of(baos.toByteArray());
        }
        catch (IOException e)
        {
            return Optional.empty();
        }
    }

    public static boolean sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException e)
        {
            return false;
        }
    }

    private static boolean stopIfHttpClientError(URLConnection conn)
    {
        if (conn instanceof HttpURLConnection)
            try
            {
                if (((HttpURLConnection) conn).getResponseCode() / 100 == 4)
                    return false;
            }
            catch (IOException e)
            {
                Retries.rethrow(e);
            }
        return true;
    }

    public static <T> ListenableFuture<T> submitTask(Callable<T> callable)
    {
        ListenableFutureTask<T> future;
        SharedPool.execute(future = ListenableFutureTask.create(callable));
        return future;
    }

}
