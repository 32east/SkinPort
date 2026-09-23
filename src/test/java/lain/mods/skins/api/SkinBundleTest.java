package lain.mods.skins.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.impl.SkinData;
import org.junit.Test;

public class SkinBundleTest
{

    private static SkinData ready(String origin, String type)
    {
        SkinData skin = new SkinData(origin);
        skin.put(new byte[] { 1, 2, 3 }, type);
        return skin;
    }

    private static SkinData fallback()
    {
        return ready("default-steve", "default").asFallback();
    }

    @Test
    public void theDefaultSkinStandsInWhileTheRealOneLoads()
    {
        SkinData mojang = new SkinData("mojang");
        SkinData steve = fallback();
        SkinBundle bundle = new SkinBundle().set(Arrays.<ISkin> asList(mojang, steve));

        // Nothing from SkinPort would mean vanilla's 64x32 texture on the 64x64 model
        assertSame(steve, bundle.resolve());
        assertTrue(bundle.isPending());

        mojang.put(new byte[] { 4, 5, 6 }, "slim");
        assertSame(mojang, bundle.resolve());
        assertFalse(bundle.isPending());
    }

    @Test
    public void theStandInGivesWayToTheRealSkin()
    {
        SkinData crafatar = new SkinData("crafatar");
        SkinData steve = fallback();
        SkinBundle bundle = new SkinBundle().set(Arrays.<ISkin> asList(crafatar, steve));

        assertSame(steve, bundle.resolve());
        assertSame(steve, bundle.resolve()); // held as the winner for a while
        crafatar.put(new byte[] { 7 }, "default");
        assertSame(crafatar, bundle.resolve());
    }

    @Test
    public void aSkinOnScreenStaysWhenAnotherProviderFinishesLater()
    {
        SkinData mojang = new SkinData("mojang");
        SkinData crafatar = ready("crafatar", "slim");
        SkinBundle bundle = new SkinBundle().set(Arrays.<ISkin> asList(mojang, crafatar, fallback()));

        assertSame(crafatar, bundle.resolve());
        mojang.put(new byte[] { 8 }, "slim");
        assertSame(crafatar, bundle.resolve());
    }

    @Test
    public void providersThatComeUpEmptyLeaveTheDefaultSkinForGood()
    {
        SkinData mojang = new SkinData("mojang");
        SkinData steve = fallback();
        SkinBundle bundle = new SkinBundle().set(Arrays.<ISkin> asList(mojang, steve));

        mojang.markSettled();
        assertSame(steve, bundle.resolve());
        assertFalse(bundle.isPending());
    }

    @Test
    public void aReloadInFlightIsStillPending()
    {
        SkinBundle bundle = new SkinBundle().set(Arrays.<ISkin> asList(ready("mojang", "slim"), fallback()));

        assertFalse(bundle.isPending());
        bundle.reloadToken.set(new Object());
        assertTrue(bundle.isPending());
    }

}
