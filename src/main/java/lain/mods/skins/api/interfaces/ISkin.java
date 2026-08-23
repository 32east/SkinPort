package lain.mods.skins.api.interfaces;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Function;

public interface ISkin
{

    /**
     * @return the ByteBuffer for the skin.
     */
    ByteBuffer getData();

    /**
     * @return "default" for classical 4-pixel arms, "slim" for 3-pixel slim arms, "legacy" for old skin format, "cape" for capes. (note that "legacy" and "cape" are not official things)
     */
    String getSkinType();

    /**
     * @return true if the ByteBuffer is ready for use.
     */
    boolean isDataReady();

    /**
     * The image and the model type that describes it, as one object.
     * <p>
     * Anything that needs both MUST take them from here rather than from separate getData() /
     * getSkinType() calls: those are two reads of two fields that a provider thread rewrites
     * while the client thread renders, so they can straddle a write and pair a slim image with
     * a "default" model - which draws the skin onto wide arms for a few frames.
     */
    default Loaded loaded()
    {
        ByteBuffer data = getData();
        return data == null ? null : new Loaded(data, getSkinType());
    }

    /**
     * An immutable (image, model type) pair.
     */
    final class Loaded
    {

        public final ByteBuffer data;
        public final String type;

        public Loaded(ByteBuffer data, String type)
        {
            this.data = data;
            this.type = type;
        }

    }

    /**
     * True when this provider has finished (success or fail) and will not receive data later.
     */
    default boolean isSettled()
    {
        return isDataReady();
    }

    /**
     * Last-resort Steve/Alex placeholder. Must not win over a real skin that is still downloading.
     */
    default boolean isFallback()
    {
        return false;
    }

    /**
     * Do cleanup when this gets called. <br>
     * Listeners will be notified before anything is done, and then, resources will be released.
     */
    void onRemoval();

    /**
     * Set a listener to be notified when {@link #onRemoval() onRemoval()} is called, and before anything is done to the resources. <br>
     * Multiple listeners will be called one by one in order.
     *
     * @param listener the listener to set.
     * @return true if successful, null and duplicates will fail.
     */
    boolean setRemovalListener(Consumer<ISkin> listener);

    /**
     * Set a filter to perform an action on the data and possibly transform it before it got pushed to the game. <br>
     * The returned buffer will be used instead of the original. <br>
     * Multiple filters will be applied one by one in a chain. <br>
     * Don't forget to {@link ByteBuffer#rewind() rewind()} before return it if you modified it's state. <br>
     * Make sure the final buffer is a direct buffer, see {@link org.lwjgl.BufferUtils BufferUtils}, otherwise the game will fail.
     *
     * @param filter the filter to set.
     * @return true if successful, null and duplicates will fail.
     */
    boolean setSkinFilter(Function<ByteBuffer, ByteBuffer> filter);

}
