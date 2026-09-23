package lain.mods.skinport.impl.forge.debug;

import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.shader.Framebuffer;

/**
 * Draws a player the way the inventory screen does - on its own, front and back side by side -
 * into an offscreen frame, and reads it back.
 * <p>
 * Everything that animates is pinned for the shot (idle arm sway, walking, swinging, the hurt
 * flash), so two shots of the same skin on the same model are identical to the bit, and any
 * difference between frames is a difference in what SkinPort handed the renderer.
 */
final class PlayerShot
{

    static final int WIDTH = 240;
    static final int HEIGHT = 184;
    /** Pixels per block. */
    static final int SCALE = 80;
    /** Screen pixels per skin pixel: RenderPlayer draws the model at 15/16 of its size. */
    static final float PX = SCALE / 16F * 0.9375F;
    static final int FRONT_X = 60;
    static final int BACK_X = 180;
    static final int FEET_Y = 176;

    private Framebuffer framebuffer;

    static boolean available()
    {
        return OpenGlHelper.isFramebufferEnabled();
    }

    ShotImage take(AbstractClientPlayer player)
    {
        Minecraft mc = Minecraft.getMinecraft();
        if (framebuffer == null)
            framebuffer = new Framebuffer(WIDTH, HEIGHT, true);

        int ticks = player.ticksExisted;
        float swing = player.swingProgress, prevSwing = player.prevSwingProgress;
        float limb = player.limbSwing, limbAmount = player.limbSwingAmount, prevLimbAmount = player.prevLimbSwingAmount;
        int hurt = player.hurtTime;
        float bodyYaw = player.renderYawOffset, prevBodyYaw = player.prevRenderYawOffset;
        float yaw = player.rotationYaw, prevYaw = player.prevRotationYaw;
        float pitch = player.rotationPitch, prevPitch = player.prevRotationPitch;
        float headYaw = player.rotationYawHead, prevHeadYaw = player.prevRotationYawHead;
        boolean hideGui = mc.gameSettings.hideGUI;
        boolean fancy = mc.gameSettings.fancyGraphics;
        float viewY = RenderManager.instance.playerViewY;
        try
        {
            player.ticksExisted = 0;
            player.swingProgress = player.prevSwingProgress = 0F;
            player.limbSwing = player.limbSwingAmount = player.prevLimbSwingAmount = 0F;
            player.hurtTime = 0;
            // No name tag (it needs a camera the loading screen does not have yet) and no shadow
            // (it needs the world, which the renderer may not have been handed yet on join)
            mc.gameSettings.hideGUI = true;
            mc.gameSettings.fancyGraphics = false;

            framebuffer.setFramebufferColor(0F, 0F, 0F, 0F);
            framebuffer.framebufferClear();
            framebuffer.bindFramebuffer(true);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0D, WIDTH, HEIGHT, 0D, 1000D, 3000D);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glTranslatef(0F, 0F, -2000F);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            try
            {
                draw(player, FRONT_X, 0F);
                draw(player, BACK_X, 180F);
            }
            finally
            {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
            }

            IntBuffer buffer = BufferUtils.createIntBuffer(WIDTH * HEIGHT);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            int[] bottomUp = new int[WIDTH * HEIGHT];
            buffer.get(bottomUp);
            int[] topDown = new int[WIDTH * HEIGHT];
            for (int y = 0; y < HEIGHT; y++)
                System.arraycopy(bottomUp, (HEIGHT - 1 - y) * WIDTH, topDown, y * WIDTH, WIDTH);
            return new ShotImage(WIDTH, HEIGHT, topDown);
        }
        finally
        {
            mc.getFramebuffer().bindFramebuffer(true);
            player.ticksExisted = ticks;
            player.swingProgress = swing;
            player.prevSwingProgress = prevSwing;
            player.limbSwing = limb;
            player.limbSwingAmount = limbAmount;
            player.prevLimbSwingAmount = prevLimbAmount;
            player.hurtTime = hurt;
            player.renderYawOffset = bodyYaw;
            player.prevRenderYawOffset = prevBodyYaw;
            player.rotationYaw = yaw;
            player.prevRotationYaw = prevYaw;
            player.rotationPitch = pitch;
            player.prevRotationPitch = prevPitch;
            player.rotationYawHead = headYaw;
            player.prevRotationYawHead = prevHeadYaw;
            mc.gameSettings.hideGUI = hideGui;
            mc.gameSettings.fancyGraphics = fancy;
            RenderManager.instance.playerViewY = viewY;
        }
    }

    /**
     * GuiInventory.func_147046_a (drawEntityOnScreen), with the angles that follow the mouse
     * replaced by fixed ones.
     */
    private static void draw(AbstractClientPlayer player, int x, float facing)
    {
        // The model is drawn through whatever lightmap is enabled, at whatever coordinates the last
        // thing drawn in the world left behind. The inventory screen can count on the GUI having
        // switched it off; a shot taken at the end of a frame cannot.
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glPushMatrix();
        GL11.glTranslatef(x, FEET_Y, 50F);
        GL11.glScalef(-SCALE, SCALE, SCALE);
        GL11.glRotatef(180F, 0F, 0F, 1F);
        GL11.glRotatef(135F, 0F, 1F, 0F);
        RenderHelper.enableStandardItemLighting();
        GL11.glRotatef(-135F, 0F, 1F, 0F);
        player.renderYawOffset = player.prevRenderYawOffset = facing;
        player.rotationYaw = player.prevRotationYaw = facing;
        player.rotationYawHead = player.prevRotationYawHead = facing;
        player.rotationPitch = player.prevRotationPitch = 0F;
        GL11.glTranslatef(0F, player.yOffset, 0F);
        RenderManager.instance.playerViewY = 180F;
        RenderManager.instance.renderEntityWithPosYaw(player, 0D, 0D, 0D, 0F, 1F);
        GL11.glPopMatrix();
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    void dispose()
    {
        if (framebuffer != null)
            framebuffer.deleteFramebuffer();
        framebuffer = null;
    }

}
