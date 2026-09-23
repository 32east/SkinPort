package lain.mods.skinport.impl.forge.debug;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

/**
 * What Mojang says an account looks like, fetched straight from Mojang with nothing of SkinPort's
 * in the way: the account id, the signed textures property, the skin image and its model. This is
 * the answer the pipeline under test has to arrive at.
 */
final class MojangReference
{

    final String name;
    volatile UUID id;
    /** "slim" or "default", as the textures metadata says. */
    volatile String model;
    volatile String textures;
    volatile String signature;
    volatile String skinUrl;
    volatile BufferedImage skin;
    volatile String capeUrl;
    /** True when Mojang answered that no such account exists. */
    volatile boolean missing;
    volatile String error;
    private final CountDownLatch done = new CountDownLatch(1);

    private MojangReference(String name)
    {
        this.name = name;
    }

    static MojangReference start(String name, Proxy proxy)
    {
        MojangReference ref = new MojangReference(name);
        Thread thread = new Thread(() -> {
            try
            {
                ref.fetch(proxy);
            }
            catch (Throwable t)
            {
                ref.error = t.toString();
            }
            finally
            {
                ref.done.countDown();
            }
        }, "SkinPort autotest reference " + name);
        thread.setDaemon(true);
        thread.start();
        return ref;
    }

    boolean isDone()
    {
        return done.getCount() == 0;
    }

    boolean await(long millis)
    {
        try
        {
            return done.await(millis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            return false;
        }
    }

    boolean hasSkin()
    {
        return skin != null && error == null;
    }

    /** The profile an online-mode server would send for this account: real id, signed textures. */
    GameProfile onlineProfile()
    {
        GameProfile profile = new GameProfile(id, name);
        if (textures != null)
            profile.getProperties().put("textures", new Property("textures", textures, signature));
        return profile;
    }

    private void fetch(Proxy proxy) throws IOException
    {
        String lookup = get("https://api.mojang.com/users/profiles/minecraft/" + name, proxy);
        if (lookup == null)
        {
            missing = true;
            return;
        }
        JsonObject account = new JsonParser().parse(lookup).getAsJsonObject();
        id = fromUndashed(account.get("id").getAsString());

        String session = get("https://sessionserver.mojang.com/session/minecraft/profile/" + account.get("id").getAsString() + "?unsigned=false", proxy);
        if (session == null)
            throw new IOException("session server has no profile for " + id);
        for (JsonElement e : new JsonParser().parse(session).getAsJsonObject().getAsJsonArray("properties"))
        {
            JsonObject property = e.getAsJsonObject();
            if ("textures".equals(property.get("name").getAsString()))
            {
                textures = property.get("value").getAsString();
                signature = property.has("signature") ? property.get("signature").getAsString() : null;
            }
        }
        if (textures == null)
            throw new IOException("no textures property for " + name);

        JsonObject decoded = new JsonParser().parse(new String(Base64.getDecoder().decode(textures), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures");
        model = "default";
        if (decoded.has("SKIN"))
        {
            JsonObject skinJson = decoded.getAsJsonObject("SKIN");
            skinUrl = skinJson.get("url").getAsString();
            if (skinJson.has("metadata") && skinJson.getAsJsonObject("metadata").has("model") && "slim".equals(skinJson.getAsJsonObject("metadata").get("model").getAsString()))
                model = "slim";
            byte[] png = download(skinUrl, proxy);
            skin = ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (skin == null)
                throw new IOException("skin at " + skinUrl + " is not an image");
        }
        if (decoded.has("CAPE"))
            capeUrl = decoded.getAsJsonObject("CAPE").get("url").getAsString();
    }

    /** @return the body, or null for 204/404 (no such account). */
    private static String get(String url, Proxy proxy) throws IOException
    {
        HttpURLConnection conn = open(url, proxy);
        int code = conn.getResponseCode();
        if (code == 204 || code == 404)
            return null;
        if (code / 100 != 2)
            throw new IOException("HTTP " + code + " from " + url);
        try (InputStream in = conn.getInputStream())
        {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] download(String url, Proxy proxy) throws IOException
    {
        HttpURLConnection conn = open(url, proxy);
        if (conn.getResponseCode() / 100 != 2)
            throw new IOException("HTTP " + conn.getResponseCode() + " from " + url);
        try (InputStream in = conn.getInputStream())
        {
            return readAll(in);
        }
    }

    private static HttpURLConnection open(String url, Proxy proxy) throws IOException
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(proxy == null ? Proxy.NO_PROXY : proxy);
        // Short first: a dead network should fail the reference in seconds, not stall the run
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setUseCaches(false);
        return conn;
    }

    private static byte[] readAll(InputStream in) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static UUID fromUndashed(String s)
    {
        return UUID.fromString(s.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"));
    }

    static UUID offlineId(String name)
    {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

}
