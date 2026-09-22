package lain.mods.skinport.impl.forge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import org.junit.Test;

public class SkinPortModelArmorTest
{

    private static final float EPSILON = 0.0001F;

    @Test
    public void slimArmorUsesThreePixelGeometryAndSlimPivots()
    {
        SkinPortModelArmor model = new SkinPortModelArmor(1.0F, true);

        assertTrue(model.smallArms);
        assertArm(model.bipedRightArm.cubeList.get(0), -2.0F, 1.0F);
        assertArm(model.bipedLeftArm.cubeList.get(0), -1.0F, 2.0F);
        assertEquals(-5.0F, model.bipedRightArm.rotationPointX, EPSILON);
        assertEquals(5.0F, model.bipedLeftArm.rotationPointX, EPSILON);
        assertEquals(2.5F, model.bipedRightArm.rotationPointY, EPSILON);
        assertEquals(2.5F, model.bipedLeftArm.rotationPointY, EPSILON);
        assertTrue(model.bipedLeftArm.mirror);
        assertEquals(64.0F, model.bipedRightArm.textureWidth, EPSILON);
        assertEquals(32.0F, model.bipedRightArm.textureHeight, EPSILON);
    }

    @Test
    public void defaultArmorKeepsVanillaArmGeometry()
    {
        SkinPortModelArmor model = new SkinPortModelArmor(1.0F, false);

        assertFalse(model.smallArms);
        assertArm(model.bipedRightArm.cubeList.get(0), -3.0F, 1.0F);
        assertArm(model.bipedLeftArm.cubeList.get(0), -1.0F, 3.0F);
        assertEquals(2.0F, model.bipedRightArm.rotationPointY, EPSILON);
        assertEquals(2.0F, model.bipedLeftArm.rotationPointY, EPSILON);
    }

    @Test
    public void slimArmorKeepsTheVanillaFourPixelUvRegions()
    {
        SkinPortModelArmor armor = new SkinPortModelArmor(1.0F, true);
        SkinPortModelArmor.SlimArmorBox box =
                (SkinPortModelArmor.SlimArmorBox) armor.bipedRightArm.cubeList.get(0);

        assertUvBounds(box.texturedQuads[0], 48.0F, 52.0F, 20.0F, 32.0F);
        assertUvBounds(box.texturedQuads[1], 40.0F, 44.0F, 20.0F, 32.0F);
        assertUvBounds(box.texturedQuads[2], 44.0F, 48.0F, 16.0F, 20.0F);
        assertUvBounds(box.texturedQuads[3], 48.0F, 52.0F, 16.0F, 20.0F);
        assertUvBounds(box.texturedQuads[4], 44.0F, 48.0F, 20.0F, 32.0F);
        assertUvBounds(box.texturedQuads[5], 52.0F, 56.0F, 20.0F, 32.0F);
    }

    @Test
    public void slimArmorDilationWrapsTheThreePixelBaseGeometry()
    {
        SkinPortModelArmor armor = new SkinPortModelArmor(1.0F, true);
        SkinPortModelArmor.SlimArmorBox box =
                (SkinPortModelArmor.SlimArmorBox) armor.bipedRightArm.cubeList.get(0);

        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        for (TexturedQuad quad : box.texturedQuads)
        {
            for (PositionTextureVertex vertex : quad.vertexPositions)
            {
                minX = Math.min(minX, (float) vertex.vector3D.xCoord);
                maxX = Math.max(maxX, (float) vertex.vector3D.xCoord);
            }
        }
        assertEquals(-3.0F, minX, EPSILON);
        assertEquals(2.0F, maxX, EPSILON);
        assertEquals(5.0F, maxX - minX, EPSILON);
    }

    private static void assertUvBounds(TexturedQuad quad, float minU, float maxU, float minV, float maxV)
    {
        float actualMinU = Float.POSITIVE_INFINITY;
        float actualMaxU = Float.NEGATIVE_INFINITY;
        float actualMinV = Float.POSITIVE_INFINITY;
        float actualMaxV = Float.NEGATIVE_INFINITY;
        for (PositionTextureVertex vertex : quad.vertexPositions)
        {
            actualMinU = Math.min(actualMinU, vertex.texturePositionX * 64.0F);
            actualMaxU = Math.max(actualMaxU, vertex.texturePositionX * 64.0F);
            actualMinV = Math.min(actualMinV, vertex.texturePositionY * 32.0F);
            actualMaxV = Math.max(actualMaxV, vertex.texturePositionY * 32.0F);
        }
        assertEquals(minU, actualMinU, EPSILON);
        assertEquals(maxU, actualMaxU, EPSILON);
        assertEquals(minV, actualMinV, EPSILON);
        assertEquals(maxV, actualMaxV, EPSILON);
    }

    private static void assertArm(ModelBox arm, float minX, float maxX)
    {
        assertEquals(minX, arm.posX1, EPSILON);
        assertEquals(maxX, arm.posX2, EPSILON);
        assertEquals(12.0F, arm.posY2 - arm.posY1, EPSILON);
        assertEquals(4.0F, arm.posZ2 - arm.posZ1, EPSILON);
    }

}
