package lain.mods.skins.impl.forge;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import lain.mods.skins.api.interfaces.ISkinTexture;
import lain.mods.skins.impl.SkinData;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

public class CustomSkinTexture extends AbstractTexture implements ISkinTexture
{

    private static BufferedImage loadImage(ByteBuffer buf)
    {
        if (buf == null)
            return null;
        ByteBuffer view = buf.duplicate();
        view.rewind();
        try (InputStream in = SkinData.wrapByteBufferAsInputStream(view))
        {
            return ImageIO.read(in);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private final ResourceLocation _location;
    private volatile ByteBuffer _data;

    public CustomSkinTexture(ResourceLocation location, ByteBuffer data)
    {
        _location = location;
        if (data == null)
            throw new IllegalArgumentException("buffer must not be null");
        _data = data;
    }

    @Override
    public ByteBuffer getData()
    {
        return _data;
    }

    public ResourceLocation getLocation()
    {
        return _location;
    }

    @Override
    public void loadTexture(IResourceManager resMan) throws IOException
    {
        ByteBuffer buf = _data;
        BufferedImage image = loadImage(buf);
        if (image == null)
        {
            // Keep the already-uploaded GPU texture. Deleting it here (the old
            // behaviour) allocates a new GL id on the next successful upload and
            // leaves Angelica / GL-state caches pointing at a recycled id — after
            // a resource-pack reload that shows the player skin as a fullscreen overlay.
            if (this.glTextureId != -1)
                return;
            throw new FileNotFoundException(getLocation().toString());
        }

        if (this.glTextureId == -1)
            TextureUtil.uploadTextureImageAllocate(getGlTextureId(), image, false, false);
        else
            TextureUtil.uploadTextureImage(this.glTextureId, image);
    }

}
