package lain.mods.skinport.init.forge;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.mojang.authlib.GameProfile;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lain.mods.skinport.impl.forge.SkinCustomization;
import lain.mods.skinport.impl.forge.SkinPortGuiCustomizeSkin;
import lain.mods.skinport.impl.forge.SkinPortModelHumanoidHead;
import lain.mods.skinport.impl.forge.SkinPortRenderPlayer;
import lain.mods.skinport.impl.forge.SpecialModel;
import lain.mods.skinport.impl.forge.SpecialRenderer;
import lain.mods.skins.api.SkinBundle;
import lain.mods.skins.api.SkinProviderAPI;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.impl.PlayerProfile;
import lain.mods.skins.impl.Shared;
import lain.mods.skins.impl.SkinData;
import lain.mods.skins.impl.SkinLog;
import lain.mods.skins.impl.forge.CustomSkinTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelSkeletonHead;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.MinecraftForgeClient;

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy implements IResourceManagerReloadListener
{

    /**
     * One frame's worth of resolved state for a player. The texture and the model type MUST come
     * out of the same snapshot: they are read at different points of the frame (the model when the
     * renderer is picked, the texture when it is bound), and the skin pipeline keeps completing in
     * the background while that happens. Sampling them independently is what produced the join
     * sequence "real skin on Steve arms" -> "Steve skin on slim arms" -> correct.
     */
    private static final class SkinState
    {

        long frame = -1L;
        boolean resolving;
        ResourceLocation location; // null: SkinPort has nothing, vanilla's own texture is drawn
        String type = "default";
        Object source;
        Object renderer;

    }

    private static final Map<String, Render> renderers = new HashMap<>();
    private static final Map<ByteBuffer, CustomSkinTexture> textures = new IdentityHashMap<>();
    private static final Map<UUID, SkinState> states = new ConcurrentHashMap<>();
    private static final SkinPortModelHumanoidHead modelHumanoidHead = new SkinPortModelHumanoidHead();
    private static final ResourceLocation VANILLA_DEFAULT_SKIN = new ResourceLocation("textures/entity/steve.png");
    private static boolean reloadListenerRegistered;
    private static volatile long frameCounter;
    private static Field downloadedImageField;
    private static boolean downloadedImageFieldResolved;

    public static ResourceLocation bindTexture(GameProfile profile, ResourceLocation result)
    {
        if (profile != null)
        {
            ISkin skin = resolveSkin(SkinProviderAPI.SKIN.getSkin(PlayerProfile.wrapGameProfile(profile)));
            if (skin != null && skin.getData() != null)
                return ClientProxy.getOrCreateTexture(skin.getData(), skin).getLocation();
        }
        return null;
    }

    public static ResourceLocation generateRandomLocation()
    {
        return new ResourceLocation("skinport", String.format("textures/generated/%s", UUID.randomUUID().toString()));
    }

    public static ModelSkeletonHead getHumanoidHead(ResourceLocation location, ModelSkeletonHead result)
    {
        ITextureObject texture = FMLClientHandler.instance().getClient().getTextureManager().getTexture(location);
        if (texture instanceof CustomSkinTexture)
            return modelHumanoidHead;
        return null;
    }

    public static ResourceLocation getLocationCape(AbstractClientPlayer player, ResourceLocation result)
    {
        ISkin skin = resolveSkin(SkinProviderAPI.CAPE.getSkin(PlayerProfile.wrapGameProfile(player.getGameProfile())));
        if (skin != null && skin.getData() != null)
            return ClientProxy.getOrCreateTexture(skin.getData(), skin).getLocation();
        return null;
    }

    public static ResourceLocation getLocationSkin(AbstractClientPlayer player, ResourceLocation result)
    {
        return resolve(player).location;
    }

    /**
     * Resolves this frame's (texture, model) pair for a player, at most once per frame.
     */
    private static SkinState resolve(AbstractClientPlayer player)
    {
        UUID uuid = player.getUniqueID();
        SkinState state = states.get(uuid);
        if (state == null)
            states.put(uuid, state = new SkinState());

        long frame = frameCounter;
        if (state.frame == frame || state.resolving)
            return state;

        boolean first = state.frame < 0L;
        state.resolving = true;
        try
        {
            ResourceLocation oldLocation = state.location;
            String oldType = state.type;
            Object oldSource = state.source;

            ISkin skin = resolveSkin(SkinProviderAPI.SKIN.getSkin(PlayerProfile.wrapGameProfile(player.getGameProfile())));
            // One snapshot: the image and the model type that goes with it must not be read
            // separately, or a provider finishing mid-read pairs a slim image with a wide model.
            ISkin.Loaded loaded = skin == null ? null : skin.loaded();
            ByteBuffer data = loaded == null ? null : loaded.data;

            // Clear first: the getLocationSkin() below re-enters our own hook, and it has to report
            // what vanilla would draw on its own, not the location left over from the last frame.
            state.location = null;
            ResourceLocation vanilla = player.getLocationSkin();
            boolean vanillaHasRealSkin = vanillaSkinReady(vanilla);

            if (data != null && !(skin.isFallback() && vanillaHasRealSkin))
            {
                // SkinPort supplies the texture, so the model has to describe THAT image.
                state.location = getOrCreateTexture(data, skin).getLocation();
                state.type = normalizeType(loaded.type, "default");
                state.source = skin;
            }
            else
            {
                // Either SkinPort has nothing yet, or all it has is its built-in Steve/Alex while
                // vanilla already holds the player's real skin - let vanilla's texture through and
                // take the model from the same metadata vanilla uses, so a slim model never ends up
                // pinned onto a wide placeholder (and vice versa) while a download is in flight.
                state.type = vanillaHasRealSkin ? profileModel(player) : "default";
                state.source = null;
            }

            if (SkinLog.enabled() && (first || oldSource != state.source || !state.type.equals(oldType) || oldLocation != state.location))
                SkinLog.debug("resolve %s [%s] -> model=%s texture=%s from=%s", player.getGameProfile().getName(), uuid, state.type, describeTexture(state.location, vanilla), SkinLog.id(state.source));
        }
        catch (Throwable t)
        {
            SkinLog.warn("failed to resolve skin for %s: %s", player.getGameProfile().getName(), t);
        }
        finally
        {
            state.frame = frame;
            state.resolving = false;
        }
        return state;
    }

    /**
     * What is actually going to be drawn, for the log: our own upload, or vanilla's texture and
     * whether that one has finished downloading.
     */
    private static String describeTexture(ResourceLocation own, ResourceLocation vanilla)
    {
        if (own != null)
            return "skinport:" + own.getResourcePath().replace("textures/generated/", "");
        if (vanilla == null)
            return "vanilla:none";
        if (VANILLA_DEFAULT_SKIN.equals(vanilla))
            return "vanilla:steve-placeholder";
        return "vanilla:" + vanilla.getResourcePath() + (vanillaSkinReady(vanilla) ? "(ready)" : "(DOWNLOADING)");
    }

    private static ISkin resolveSkin(ISkin skin)
    {
        if (skin instanceof SkinBundle)
            return ((SkinBundle) skin).resolve();
        if (skin != null && skin.isDataReady())
            return skin;
        return null;
    }

    /**
     * Whether vanilla is drawing this player's actual skin right now.
     * <p>
     * Vanilla registers the "skins/&lt;hash&gt;" location as soon as the profile is known, but
     * ThreadDownloadImageData keeps drawing its fallback - the wide Steve - until the download
     * lands. Trusting the location alone would call that a real skin and pin a slim model onto
     * Steve's arms for as long as the download takes.
     */
    private static boolean vanillaSkinReady(ResourceLocation location)
    {
        if (location == null || VANILLA_DEFAULT_SKIN.equals(location))
            return false;
        ITextureObject texture = FMLClientHandler.instance().getClient().getTextureManager().getTexture(location);
        if (!(texture instanceof ThreadDownloadImageData))
            return true;
        Field field = downloadedImageField();
        if (field == null)
            return true; // cannot tell - assume vanilla has the real thing
        try
        {
            return field.get(texture) != null;
        }
        catch (Throwable t)
        {
            return true;
        }
    }

    /**
     * ThreadDownloadImageData's downloaded image, located by type rather than by name: the field
     * is called something different in a dev workspace than it is at runtime.
     */
    private static Field downloadedImageField()
    {
        if (!downloadedImageFieldResolved)
        {
            downloadedImageFieldResolved = true;
            for (Field f : ThreadDownloadImageData.class.getDeclaredFields())
            {
                if (f.getType() == BufferedImage.class)
                {
                    f.setAccessible(true);
                    downloadedImageField = f;
                    break;
                }
            }
            if (downloadedImageField == null)
                SkinLog.warn("no BufferedImage field on ThreadDownloadImageData - cannot tell a downloading skin from a finished one");
        }
        return downloadedImageField;
    }

    /**
     * The model Mojang publishes for this account. Available as soon as the profile is filled,
     * which is well before any skin image finishes downloading.
     */
    private static String profileModel(AbstractClientPlayer player)
    {
        String hint = Shared.getModelHint(PlayerProfile.wrapGameProfile(player.getGameProfile()).getOriginal());
        if (hint == null)
            hint = Shared.getModelHint(player.getGameProfile());
        return normalizeType(hint, "default");
    }

    private static String normalizeType(String type, String fallback)
    {
        if ("slim".equals(type) || "default".equals(type))
            return type;
        String normalized = SkinData.normalizeModelHint(type);
        return normalized != null ? normalized : fallback;
    }

    public static synchronized CustomSkinTexture getOrCreateTexture(ByteBuffer data, ISkin skin)
    {
        CustomSkinTexture existing = textures.get(data);
        if (existing != null)
            return existing;

        final CustomSkinTexture texture = new CustomSkinTexture(generateRandomLocation(), data);
        FMLClientHandler.instance().getClient().getTextureManager().loadTexture(texture.getLocation(), texture);
        textures.put(data, texture);
        SkinLog.debug("uploaded texture %s (%d bytes) for %s", texture.getLocation().getResourcePath(), data.capacity(), SkinLog.id(skin));

        if (skin != null)
        {
            skin.setRemovalListener(s -> {
                if (data == s.getData())
                {
                    // addScheduledTask
                    FMLClientHandler.instance().getClient().func_152344_a(() -> {
                        SkinLog.debug("deleting texture %s of %s", texture.getLocation().getResourcePath(), SkinLog.id(s));
                        FMLClientHandler.instance().getClient().getTextureManager().deleteTexture(texture.getLocation());
                        synchronized (ClientProxy.class)
                        {
                            textures.remove(data);
                        }
                    });
                }
            });
        }
        return texture;
    }

    public static Render getPlayerRenderer(RenderManager manager, AbstractClientPlayer player, Render result)
    {
        if (renderers.isEmpty())
            setupRenderers(manager);
        SkinState state = resolve(player);
        result = renderers.getOrDefault(state.type, result);
        if (SkinLog.enabled() && state.renderer != result)
        {
            state.renderer = result;
            SkinLog.debug("renderer for %s -> %s (model=%s)", player.getGameProfile().getName(), SkinLog.id(result), state.type);
        }
        if (result instanceof SpecialRenderer)
            ((SpecialRenderer) result).onGetRenderer(manager, player);
        return result;
    }

    public static String getSkinType(AbstractClientPlayer player)
    {
        return resolve(player).type;
    }

    public static boolean hasCape(AbstractClientPlayer player, boolean result)
    {
        return player.getLocationCape() != null;
    }

    public static boolean hasSkin(AbstractClientPlayer player, boolean result)
    {
        return player.getLocationSkin() != null;
    }

    public static int initHeight(ModelBiped model, int textureHeight)
    {
        if (model instanceof SpecialModel)
            return ((SpecialModel) model).initHeight();
        return textureHeight;
    }

    public static int initWidth(ModelBiped model, int textureWidth)
    {
        if (model instanceof SpecialModel)
            return ((SpecialModel) model).initWidth();
        return textureWidth;
    }

    @SideOnly(Side.CLIENT)
    public static void onButtonAction(GuiOptions gui, GuiButton button)
    {
        if (!button.enabled || button.id != 110)
            return;
        gui.mc.gameSettings.saveOptions();
        gui.mc.displayGuiScreen(new SkinPortGuiCustomizeSkin(gui));
    }

    public static void setupButton(GuiOptions gui, List<GuiButton> buttonList)
    {
        buttonList.add(new GuiButton(110, gui.width / 2 - 155, gui.height / 6 + 48 - 6, 150, 20, I18n.format("options.skinCustomisation")));
    }

    /**
     * True when the local player would otherwise be drawn from inside their own
     * head (first-person world pass). Inventory / GUI previews use render pass -1
     * and must still draw the full model.
     */
    public static boolean shouldSkipFirstPersonPlayer(EntityPlayer player)
    {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || player == null || mc.gameSettings == null)
            return false;
        if (player != mc.renderViewEntity)
            return false;
        if (mc.gameSettings.thirdPersonView != 0)
            return false;
        if (player.isPlayerSleeping())
            return false;
        return MinecraftForgeClient.getRenderPass() >= 0;
    }

    public static void setupRenderers(RenderManager manager)
    {
        try
        {
            if (Loader.isModLoaded("moreplayermodels")) // Compatibility with MorePlayerModels
            {
                Class<?> cls = Class.forName("lain.mods.skinport.impl.forge.compat.SkinPortRenderPlayer_MPM");
                renderers.put("default", (Render) cls.getConstructor(RenderManager.class, boolean.class).newInstance(manager, false));
                renderers.put("slim", (Render) cls.getConstructor(RenderManager.class, boolean.class).newInstance(manager, true));
                return;
            }
            if (Loader.isModLoaded("RenderPlayerAPI")) // Compatibility with RenderPlayerAPI
            {
                Class<?> cls = Class.forName("lain.mods.skinport.impl.forge.compat.SkinPortRenderPlayer_RPA");
                renderers.put("default", (Render) cls.getConstructor(RenderManager.class, boolean.class).newInstance(manager, false));
                renderers.put("slim", (Render) cls.getConstructor(RenderManager.class, boolean.class).newInstance(manager, true));
                return;
            }
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        renderers.put("default", new SkinPortRenderPlayer(manager, false));
        renderers.put("slim", new SkinPortRenderPlayer(manager, true));
    }

    @Override
    public void onResourceManagerReload(IResourceManager manager)
    {
        renderers.clear();
        // Nothing here may touch GL directly. A raw glBindTexture(0) used to sit here to clear a
        // stale binding after a reload; under Angelica it did the opposite - it moved the driver
        // while Angelica's mirror kept pointing at the old texture, so every later bind of that id
        // was skipped as redundant and the player was drawn with whatever terrain left bound.
        // CustomSkinTexture no longer drops its GPU texture on a failed reload, which is what the
        // stale binding came from in the first place.
    }

    public void registerReloadListener()
    {
        if (reloadListenerRegistered)
            return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getResourceManager() instanceof SimpleReloadableResourceManager)
        {
            ((SimpleReloadableResourceManager) mc.getResourceManager()).registerReloadListener(this);
            reloadListenerRegistered = true;
        }
    }

    /**
     * One resolution per rendered frame: everything drawn in a frame then agrees on the same
     * texture/model pair, no matter in which order the renderer and the texture binding ask.
     */
    @SubscribeEvent
    public void handleRenderTicks(TickEvent.RenderTickEvent event)
    {
        if (event.phase == TickEvent.Phase.START)
            frameCounter++;
    }

    @SubscribeEvent
    public void handleClientTicks(TickEvent.ClientTickEvent event)
    {
        if (event.phase == TickEvent.Phase.START)
        {
            frameCounter++;
            registerReloadListener();
            World world = Minecraft.getMinecraft().theWorld;
            if (world != null)
            {
                for (Object obj : world.playerEntities)
                {
                    EntityPlayer player = (EntityPlayer) obj;
                    SkinProviderAPI.SKIN.getSkin(PlayerProfile.wrapGameProfile(player.getGameProfile()));
                    SkinProviderAPI.CAPE.getSkin(PlayerProfile.wrapGameProfile(player.getGameProfile()));
                }
                if (TileEntityRendererDispatcher.instance.getSpecialRendererByClass(TileEntitySkull.class).getClass() != TileEntitySkullRenderer.class) // Aggressively restore vanilla TileEntitySkullRenderer
                    ClientRegistry.bindTileEntitySpecialRenderer(TileEntitySkull.class, new TileEntitySkullRenderer());
            }
        }
    }

    @SubscribeEvent
    public void handleEvent(ClientDisconnectionFromServerEvent event)
    {
        SkinCustomization.Flags.clear(Side.CLIENT);
        states.clear();
    }

}
