package lain.mods.skinport.impl.forge;

import java.lang.reflect.Method;
import org.lwjgl.opengl.GL11;
import lain.mods.skins.impl.SkinLog;

/**
 * GL state changes that Angelica can see.
 * <p>
 * Angelica mirrors GL state in its own GLStateManager and skips any call it believes is redundant -
 * binding the texture it already thinks is bound never reaches the driver at all. It rewrites GL11
 * calls inside vanilla and a short list of mods, but not ours, so a raw GL11 call from here changes
 * the driver behind its back and every later call is decided against a stale mirror. That is how a
 * player ends up drawn with whatever terrain rendering left bound - the block atlas - while the
 * inventory preview, which binds in a different order, still looks right.
 */
public class GlCompat
{

    private static final Method glEnable;
    private static final Method glDisable;

    static
    {
        Method enable = null;
        Method disable = null;
        try
        {
            Class<?> manager = Class.forName("com.gtnewhorizons.angelica.glsm.GLStateManager");
            enable = manager.getMethod("glEnable", int.class);
            disable = manager.getMethod("glDisable", int.class);
        }
        catch (Throwable t)
        {
            enable = null;
            disable = null;
        }
        glEnable = enable;
        glDisable = disable;
    }

    public static void disable(int cap)
    {
        if (glDisable != null)
        {
            try
            {
                glDisable.invoke(null, cap);
                return;
            }
            catch (Throwable t)
            {
                SkinLog.warn("GLStateManager.glDisable failed, falling back to raw GL11: %s", t);
            }
        }
        GL11.glDisable(cap);
    }

    public static void enable(int cap)
    {
        if (glEnable != null)
        {
            try
            {
                glEnable.invoke(null, cap);
                return;
            }
            catch (Throwable t)
            {
                SkinLog.warn("GLStateManager.glEnable failed, falling back to raw GL11: %s", t);
            }
        }
        GL11.glEnable(cap);
    }

    /**
     * @return true when Angelica's state mirror is in use, so nothing here may touch GL directly.
     */
    public static boolean managed()
    {
        return glEnable != null;
    }

}
