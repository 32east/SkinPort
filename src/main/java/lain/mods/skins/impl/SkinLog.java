package lain.mods.skins.impl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Debug tracing for the skin pipeline.
 * <p>
 * Enabled by the {@code debugLogging} option in skinport.cfg, or by
 * {@code -Dskinport.debug=true} on the command line. Everything goes to the
 * normal game log with a {@code [SkinPort]} prefix, so the join sequence (which
 * provider won, which model was picked, when the bundle was reloaded) can be
 * read straight out of latest.log.
 */
public class SkinLog
{

    private static final Logger LOGGER = LogManager.getLogger("SkinPort");

    private static volatile boolean enabled = Boolean.getBoolean("skinport.debug");

    public static void debug(String format, Object... args)
    {
        if (!enabled)
            return;
        LOGGER.info("[SkinPort] {}", safeFormat(format, args));
    }

    public static boolean enabled()
    {
        return enabled;
    }

    /**
     * Short, stable identifier of an object for correlating log lines.
     */
    public static String id(Object o)
    {
        if (o == null)
            return "none";
        return o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    public static void setEnabled(boolean value)
    {
        enabled = value || Boolean.getBoolean("skinport.debug");
    }

    public static void warn(String format, Object... args)
    {
        LOGGER.warn("[SkinPort] {}", safeFormat(format, args));
    }

    private static String safeFormat(String format, Object... args)
    {
        if (args == null || args.length == 0)
            return format;
        try
        {
            return String.format(format, args);
        }
        catch (Throwable t)
        {
            return format;
        }
    }

}
