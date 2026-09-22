package lain.mods.skinport.impl.forge;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.Tessellator;

/**
 * Vanilla armor geometry with arms aligned to SkinPort's selected player model.
 *
 * <p>Armor textures only have the vanilla 4-pixel arm layout. Slim arms use 3-pixel geometry while
 * {@link SlimArmorBox} keeps the original 4-pixel UV regions, preventing transparent or unrelated
 * texture fragments from appearing on individual faces.</p>
 */
@SideOnly(Side.CLIENT)
public class SkinPortModelArmor extends ModelBiped
{

    public final boolean smallArms;

    public SkinPortModelArmor(float z, boolean smallArms)
    {
        super(z);

        this.smallArms = smallArms;
        if (!smallArms)
            return;

        bipedRightArm = createSlimArm(-2.0F, false, z);
        bipedRightArm.setRotationPoint(-5.0F, 2.5F, 0.0F);

        bipedLeftArm = createSlimArm(-1.0F, true, z);
        bipedLeftArm.setRotationPoint(5.0F, 2.5F, 0.0F);
    }

    private ModelRenderer createSlimArm(float x, boolean mirror, float dilation)
    {
        ModelRenderer arm = new ModelRenderer(this, 40, 16);
        arm.mirror = mirror;
        arm.cubeList.add(new SlimArmorBox(arm, x, -2.0F, -2.0F, 12, 4, dilation));
        return arm;
    }

    static final class SlimArmorBox extends ModelBox
    {

        private static final int TEXTURE_X = 40;
        private static final int TEXTURE_Y = 16;
        private static final int GEOMETRY_WIDTH = 3;
        private static final int TEXTURE_WIDTH = 4;

        final TexturedQuad[] texturedQuads;

        SlimArmorBox(ModelRenderer renderer, float x, float y, float z, int height, int depth, float dilation)
        {
            super(renderer, TEXTURE_X, TEXTURE_Y, x, y, z, GEOMETRY_WIDTH, height, depth, dilation);

            float maxX = x + GEOMETRY_WIDTH;
            float maxY = y + height;
            float maxZ = z + depth;
            x -= dilation;
            y -= dilation;
            z -= dilation;
            maxX += dilation;
            maxY += dilation;
            maxZ += dilation;

            if (renderer.mirror)
            {
                float swap = maxX;
                maxX = x;
                x = swap;
            }

            PositionTextureVertex nearTopLeft = vertex(x, y, z);
            PositionTextureVertex nearTopRight = vertex(maxX, y, z);
            PositionTextureVertex nearBottomRight = vertex(maxX, maxY, z);
            PositionTextureVertex nearBottomLeft = vertex(x, maxY, z);
            PositionTextureVertex farTopLeft = vertex(x, y, maxZ);
            PositionTextureVertex farTopRight = vertex(maxX, y, maxZ);
            PositionTextureVertex farBottomRight = vertex(maxX, maxY, maxZ);
            PositionTextureVertex farBottomLeft = vertex(x, maxY, maxZ);

            texturedQuads = new TexturedQuad[] {
                    quad(renderer, new PositionTextureVertex[] {farTopRight, nearTopRight, nearBottomRight, farBottomRight},
                            TEXTURE_X + depth + TEXTURE_WIDTH, TEXTURE_Y + depth,
                            TEXTURE_X + depth + TEXTURE_WIDTH + depth, TEXTURE_Y + depth + height),
                    quad(renderer, new PositionTextureVertex[] {nearTopLeft, farTopLeft, farBottomLeft, nearBottomLeft},
                            TEXTURE_X, TEXTURE_Y + depth, TEXTURE_X + depth, TEXTURE_Y + depth + height),
                    quad(renderer, new PositionTextureVertex[] {farTopRight, farTopLeft, nearTopLeft, nearTopRight},
                            TEXTURE_X + depth, TEXTURE_Y, TEXTURE_X + depth + TEXTURE_WIDTH, TEXTURE_Y + depth),
                    quad(renderer, new PositionTextureVertex[] {nearBottomRight, nearBottomLeft, farBottomLeft, farBottomRight},
                            TEXTURE_X + depth + TEXTURE_WIDTH, TEXTURE_Y + depth,
                            TEXTURE_X + depth + TEXTURE_WIDTH + TEXTURE_WIDTH, TEXTURE_Y),
                    quad(renderer, new PositionTextureVertex[] {nearTopRight, nearTopLeft, nearBottomLeft, nearBottomRight},
                            TEXTURE_X + depth, TEXTURE_Y + depth,
                            TEXTURE_X + depth + TEXTURE_WIDTH, TEXTURE_Y + depth + height),
                    quad(renderer, new PositionTextureVertex[] {farTopLeft, farTopRight, farBottomRight, farBottomLeft},
                            TEXTURE_X + depth + TEXTURE_WIDTH + depth, TEXTURE_Y + depth,
                            TEXTURE_X + depth + TEXTURE_WIDTH + depth + TEXTURE_WIDTH, TEXTURE_Y + depth + height)
            };

            if (renderer.mirror)
            {
                for (TexturedQuad texturedQuad : texturedQuads)
                    texturedQuad.flipFace();
            }
        }

        private static PositionTextureVertex vertex(float x, float y, float z)
        {
            return new PositionTextureVertex(x, y, z, 0.0F, 0.0F);
        }

        private static TexturedQuad quad(ModelRenderer renderer, PositionTextureVertex[] vertices,
                int minU, int minV, int maxU, int maxV)
        {
            return new TexturedQuad(vertices, minU, minV, maxU, maxV, renderer.textureWidth, renderer.textureHeight);
        }

        @Override
        @SideOnly(Side.CLIENT)
        public void render(Tessellator tessellator, float scale)
        {
            for (TexturedQuad texturedQuad : texturedQuads)
                texturedQuad.draw(tessellator, scale);
        }

    }

}
