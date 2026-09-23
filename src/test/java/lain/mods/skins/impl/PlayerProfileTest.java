package lain.mods.skins.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import lain.mods.skins.api.SkinBundle;
import lain.mods.skins.api.SkinProviderAPI;
import lain.mods.skins.api.interfaces.IPlayerProfile;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.api.interfaces.ISkinProvider;
import lain.mods.skins.api.interfaces.ISkinProviderService;
import org.junit.Test;

public class PlayerProfileTest
{

    private static final String NAME = "Someone";
    private static final UUID OFFLINE = UUID.nameUUIDFromBytes(("OfflinePlayer:" + NAME).getBytes(StandardCharsets.UTF_8));
    private static final UUID ONLINE = UUID.fromString("4a2b6c8d-1e3f-4a5b-8c7d-9e0f1a2b3c4d");

    /** PlayerProfile holds its GameProfile weakly (the entity owns it); the test has to hold them. */
    private final List<GameProfile> held = new ArrayList<>();

    private GameProfile profile(UUID id)
    {
        GameProfile profile = new GameProfile(id, NAME);
        held.add(profile);
        return profile;
    }

    private GameProfile withTextures(UUID id, String textures)
    {
        GameProfile profile = profile(id);
        profile.getProperties().put("textures", new Property("textures", textures));
        return profile;
    }

    @Test
    public void keepsItsIdentityWhileItIsResolved()
    {
        PlayerProfile profile = new PlayerProfile(profile(OFFLINE));
        Map<PlayerProfile, String> cache = new HashMap<>();
        cache.put(profile, "bundle");
        int hash = profile.hashCode();

        // An offline-mode player gets looked up by name and then filled: the id underneath changes
        profile.set(profile(ONLINE));
        profile.set(withTextures(ONLINE, "blob"));

        assertEquals(ONLINE, profile.getPlayerID());
        assertEquals(OFFLINE, profile.getOriginalID());
        assertEquals(hash, profile.hashCode());
        assertEquals("bundle", cache.get(profile));
        // A second wrapper around a profile with the same id and name is the same player
        assertEquals(profile, new PlayerProfile(profile(OFFLINE)));
    }

    /** A provider whose skins the test completes by hand. */
    private static final class ManualProvider implements ISkinProvider
    {

        final List<SkinData> handedOut = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ISkin getSkin(IPlayerProfile profile)
        {
            SkinData skin = new SkinData("manual");
            handedOut.add(skin);
            return skin;
        }

    }

    @Test
    public void aReloadAppliesAsSoonAsItsSkinArrives() throws Exception
    {
        ISkinProviderService service = SkinProviderAPI.create();
        ManualProvider provider = new ManualProvider();
        SkinData steve = new SkinData("default-steve");
        steve.put(new byte[] { 1 }, "default");
        steve.asFallback();
        service.registerProvider(provider);
        service.registerProvider(p -> steve);

        PlayerProfile profile = new PlayerProfile(withTextures(ONLINE, "old"));
        SkinBundle bundle = (SkinBundle) service.getSkin(profile);
        provider.handedOut.get(0).markSettled(); // the first lookup found nothing
        assertSame(steve, bundle.resolve());

        // The skin changed: the profile update starts a reload with fresh skins
        profile.set(withTextures(ONLINE, "new"));
        long deadline = System.currentTimeMillis() + 2000;
        while (provider.handedOut.size() < 2 && System.currentTimeMillis() < deadline)
            Thread.sleep(5);
        assertEquals(2, provider.handedOut.size());
        assertTrue("reloading counts as pending", bundle.isPending());

        SkinData fresh = provider.handedOut.get(1);
        fresh.put(new byte[] { 2 }, "slim");
        long arrived = System.nanoTime();
        while (bundle.resolve() != fresh && System.nanoTime() - arrived < TimeUnit.SECONDS.toNanos(3))
            Thread.sleep(2);
        long tookMs = (System.nanoTime() - arrived) / 1000000L;

        assertSame(fresh, bundle.resolve());
        // It used to wait on a pool thread, sleeping a second at a time
        assertTrue("reload applied after " + tookMs + " ms", tookMs < 300);
    }

}
