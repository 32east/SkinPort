package lain.mods.skinport.impl.forge.debug;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import lain.mods.skinport.impl.forge.SkinCustomization;
import lain.mods.skinport.impl.forge.SkinPortGuiCustomizeSkin;
import lain.mods.skinport.impl.forge.SkinPortModelPlayer;
import lain.mods.skinport.impl.forge.SkinPortRenderPlayer;
import lain.mods.skinport.init.forge.ClientProxy;
import lain.mods.skinport.init.forge.ForgeSkinPort;
import lain.mods.skins.impl.LegacyConversion;
import lain.mods.skins.impl.SkinData;
import lain.mods.skins.impl.forge.CustomSkinTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.GuiScreenEvent;

/**
 * Scripted in-game test of the skin pipeline, watched from the very first frame a player exists.
 * <p>
 * Every frame of a join is drawn offscreen the way the inventory draws the player, in a pose that
 * never changes, so the only thing that can change between frames is the skin and the model
 * SkinPort hands the renderer. The frames are then read back against what Mojang itself says the
 * account looks like (fetched separately, straight from Mojang): which frames showed something
 * else, whether a 64x32 texture was ever stretched over the 64x64 model, how long the real skin
 * took and whether it stayed. Covered: the first join, the texture and arm model it settles on,
 * the skin layers, the customisation screen, a resource reload, other players (offline-mode,
 * online-mode, and a name with no account) and joining again after a while in the menu.
 * <p>
 * Enabled with {@code -Dskinport.autotest=true} ({@code ./gradlew runClient -Pautotest}). Results go
 * to the log as {@code [autotest] PASS/FAIL} lines and to {@code skinport-autotest.txt}; frame
 * strips and shots go to {@code screenshots/sp-*.png}. Afterwards the game stays open and quits by
 * itself after a minute without input ({@code -Dskinport.autotest.idleExitSeconds}).
 */
@SideOnly(Side.CLIENT)
public class AutoTest
{

    private static final class Step
    {

        final String name;
        final int wait;
        final Runnable action;
        /** Checked every tick after the action ran; the step is done when it returns true. */
        final BooleanSupplier until;
        final int timeout;

        Step(String name, int wait, Runnable action, BooleanSupplier until, int timeout)
        {
            this.name = name;
            this.wait = wait;
            this.action = action;
            this.until = until;
            this.timeout = timeout;
        }

    }

    public static final boolean ENABLED = Boolean.getBoolean("skinport.autotest");
    private static final Logger LOGGER = LogManager.getLogger("SkinPort");
    /** How long to wait at the menu before creating the world; big packs need longer. */
    private static final int MENU_WAIT = Integer.getInteger("skinport.autotest.menuWait", 40);
    private static final String WORLD = System.getProperty("skinport.autotest.world", "sptest");
    /** After the run the game stays open, and quits once nobody has touched it for this long. */
    private static final long IDLE_EXIT_MS = Long.getLong("skinport.autotest.idleExitSeconds", 60L) * 1000L;
    /** Time in the menu before joining again: longer than anything SkinPort keeps only while it is used. */
    private static final int REJOIN_WAIT_SECONDS = Integer.getInteger("skinport.autotest.rejoinWait", 20);
    /** A real account, seen as an offline-mode server shows it: offline id, no textures. */
    private static final String OFFLINE_NAME = System.getProperty("skinport.autotest.offlineName", "Notch");
    /** A real account, seen as an online-mode server shows it: real id and signed textures. */
    private static final String ONLINE_NAME = System.getProperty("skinport.autotest.onlineName", "jeb_");
    /** A resource reload takes minutes in a big pack; it can be left out there. */
    private static final boolean RELOAD = !Boolean.getBoolean("skinport.autotest.skipReload");
    private static final boolean REJOIN = !Boolean.getBoolean("skinport.autotest.skipRejoin");
    /** How a join is watched: at least this long, until nothing changed for the quiet time, at most the max. */
    private static final long WATCH_MIN_MS = Long.getLong("skinport.autotest.watchMinMs", 12000L);
    private static final long WATCH_QUIET_MS = Long.getLong("skinport.autotest.watchQuietMs", 5000L);
    private static final long WATCH_MAX_MS = Long.getLong("skinport.autotest.watchMaxMs", 45000L);
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int STEP_TIMEOUT = 600;
    private static final int RECORDING_TIMEOUT = (int) (WATCH_MAX_MS / 50L) + 400;
    private static final ResourceLocation VANILLA_STEVE = new ResourceLocation("textures/entity/steve.png");
    private static final SkinCustomization[] LAYERS = { SkinCustomization.hat, SkinCustomization.jacket, SkinCustomization.left_sleeve, SkinCustomization.right_sleeve, SkinCustomization.left_pants_leg, SkinCustomization.right_pants_leg };

    private final Minecraft mc = Minecraft.getMinecraft();
    private final List<Step> steps = new ArrayList<>();
    private final List<String> report = new ArrayList<>();
    private final PlayerShot shot = new PlayerShot();
    private int stepIndex = -1;
    private int ticks;
    private int passed;
    private int failed;
    private boolean started;
    private boolean waitingForStep;
    private int waitedInStep;
    private long framesRendered;
    private long framesAtStepEnd;
    private Consumer<Float> pendingProbe;

    private final MojangReference self;
    private final MojangReference offlineRef;
    private final MojangReference onlineRef;
    private final String unknownName;
    private final MojangReference unknownRef;

    /** The recording that is being filled, and whose player it follows. */
    private Recording recording;
    private Supplier<AbstractClientPlayer> recordingSubject;
    private String lastRecordedState;
    private int worldShots;

    private boolean startedWorld;
    private int savedFlags;
    private String savedOptionsFile;
    private int savedGuiScale = -1;
    private int savedThirdPerson;
    private float savedFov;
    private boolean savedPauseOnLostFocus = true;
    /** The skin button saves options.txt with the test's temporary settings in it; save it again once they are back. */
    private boolean optionsWritten;
    /** What the local player looks like once settled: every later shot of them must come out the same. */
    private ShotImage ownSkin;
    private EntityOtherPlayerMP dummy;
    private static final int DUMMY_ID = -515151;
    private long leftWorldAt;

    private boolean idleWatch;
    private long lastActivity;
    private String lastFingerprint = "";

    public AutoTest()
    {
        String own = mc.getSession().getUsername();
        self = MojangReference.start(own, mc.getProxy());
        offlineRef = MojangReference.start(OFFLINE_NAME, mc.getProxy());
        onlineRef = MojangReference.start(ONLINE_NAME, mc.getProxy());
        // Sixteen characters nobody has registered: SkinPort has to settle on a default skin for it
        Random random = new Random();
        StringBuilder name = new StringBuilder("sp");
        while (name.length() < 16)
            name.append("abcdefghijkmnpqrstuvwxyz23456789".charAt(random.nextInt(32)));
        unknownName = name.toString();
        unknownRef = MojangReference.start(unknownName, mc.getProxy());
        buildSteps();
    }

    // --- harness ---------------------------------------------------------------------------------

    private void step(String name, int wait, Runnable action)
    {
        steps.add(new Step(name, wait, action, null, STEP_TIMEOUT));
    }

    private void stepUntil(String name, int wait, Runnable action, BooleanSupplier until, int timeout)
    {
        steps.add(new Step(name, wait, action, until, timeout));
    }

    /** A step whose body runs in the render thread at the end of a full frame. */
    private void probe(String name, int wait, Consumer<Float> body)
    {
        steps.add(new Step(name, wait, () -> pendingProbe = body, () -> pendingProbe == null, STEP_TIMEOUT));
    }

    private void check(boolean ok, String name, String detail)
    {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail == null || detail.isEmpty() ? "" : ": " + detail);
        if (ok)
            passed++;
        else
            failed++;
        report.add(line);
        if (ok)
            LOGGER.info("[autotest] " + line);
        else
            LOGGER.error("[autotest] " + line);
        writeReport(null);
    }

    private void note(String text)
    {
        report.add("NOTE " + text);
        LOGGER.info("[autotest] " + text);
        writeReport(null);
    }

    /**
     * Rewritten after every line, so a run that is cut short (the window closed while it waits in
     * the menu, a crash) still leaves what it found so far.
     */
    private void writeReport(String summary)
    {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(mc.mcDataDir, "skinport-autotest.txt")), StandardCharsets.UTF_8)))
        {
            for (String line : report)
                out.println(line);
            out.println(summary != null ? summary : "(still running: passed=" + passed + " failed=" + failed + ")");
        }
        catch (Exception e)
        {
            LOGGER.error("[autotest] could not write the report", e);
        }
    }

    /** While the test sits in the menu on purpose, it says so: the menu alone looks like the end. */
    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event)
    {
        if (leftWorldAt == 0L || !(event.gui instanceof GuiMainMenu) || stepIndex >= steps.size())
            return;
        long left = REJOIN_WAIT_SECONDS - (System.currentTimeMillis() - leftWorldAt) / 1000L;
        String text = "SkinPort autotest: joining again in " + Math.max(0L, left) + " s";
        mc.fontRenderer.drawStringWithShadow(text, (event.gui.width - mc.fontRenderer.getStringWidth(text)) / 2, 4, 0xFFFF55);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (idleWatch)
        {
            watchIdle();
            return;
        }
        if (stepIndex >= steps.size())
            return;
        ticks++;
        if (!started)
        {
            started = true;
            stepIndex = 0;
            ticks = 0;
        }
        Step step = steps.get(stepIndex);
        if (!waitingForStep)
        {
            if (ticks < step.wait || framesRendered - framesAtStepEnd < 2)
                return;
            try
            {
                step.action.run();
            }
            catch (Throwable t)
            {
                check(false, step.name, "threw " + t);
                LOGGER.error("[autotest] step " + step.name + " threw", t);
            }
            waitingForStep = step.until != null;
            waitedInStep = 0;
        }
        else
        {
            waitedInStep++;
        }
        if (waitingForStep)
        {
            boolean done;
            try
            {
                done = step.until.getAsBoolean();
            }
            catch (Throwable t)
            {
                check(false, step.name, "threw " + t);
                LOGGER.error("[autotest] step " + step.name + " threw", t);
                done = true;
            }
            if (!done && waitedInStep < step.timeout)
                return;
            if (!done)
            {
                check(false, step.name, "timed out");
                pendingProbe = null;
            }
            waitingForStep = false;
        }
        stepIndex++;
        ticks = 0;
        framesAtStepEnd = framesRendered;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderTick(TickEvent.RenderTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        framesRendered++;
        if (mc.theWorld == null || mc.thePlayer == null)
            return;
        if (recording != null)
        {
            try
            {
                record();
            }
            catch (Throwable t)
            {
                check(false, recording.name + ": recording a frame", "threw " + t);
                LOGGER.error("[autotest] recording threw", t);
                recording = null;
            }
            finally
            {
                mc.getFramebuffer().bindFramebuffer(true);
            }
        }
        Consumer<Float> probe = pendingProbe;
        if (probe == null)
            return;
        pendingProbe = null;
        try
        {
            probe.accept(event.renderTickTime);
        }
        catch (Throwable t)
        {
            check(false, "probe", "threw " + t);
            LOGGER.error("[autotest] probe threw", t);
        }
        finally
        {
            mc.getFramebuffer().bindFramebuffer(true);
        }
    }

    // --- recording ---------------------------------------------------------------------------------

    private void startRecording(String name, Supplier<AbstractClientPlayer> subject)
    {
        recording = new Recording(name, WATCH_MIN_MS, WATCH_QUIET_MS, WATCH_MAX_MS);
        recordingSubject = subject;
        lastRecordedState = null;
        worldShots = 0;
    }

    private void record()
    {
        AbstractClientPlayer player = recordingSubject.get();
        // Nothing can draw an entity before the first world frame hands the render manager its
        // texture manager; the join is watched from the first frame a player can be drawn at all
        if (player == null || RenderManager.instance.renderEngine == null)
            return;
        ShotImage image = shot.take(player);
        boolean[] skewed = new boolean[1];
        String state = describe(player, skewed);
        recording.add(image, state, skewed[0]);
        String key = image.hash() + "|" + state;
        if (!key.equals(lastRecordedState))
        {
            lastRecordedState = key;
            // What a player actually saw: the world, where the player stands in front of the camera
            if (player == mc.thePlayer && mc.currentScreen == null && mc.gameSettings.thirdPersonView == 2 && worldShots < 8)
                screenshot(String.format("sp-%s-world-%02d-%dms", slug(recording.name), worldShots++, recording.elapsedMs()));
        }
        if (recording.done())
        {
            LOGGER.info("[autotest] {}: recorded {} frame(s) in {} ms", recording.name, recording.samples.size(), recording.elapsedMs());
            finished = recording;
            recording = null;
        }
    }

    /** The last recording that ended; the step that analyses it takes it. */
    private Recording finished;

    /**
     * What SkinPort handed the renderer for this player this frame: where the texture came from,
     * its size, and the model it is drawn on. A model whose texture height does not match the
     * texture's (64x32 image on the 64x64 model or the other way round) is flagged as skewed.
     */
    private String describe(AbstractClientPlayer player, boolean[] skewed)
    {
        Render render = RenderManager.instance.getEntityRenderObject(player);
        String model;
        int modelW = 64, modelH = 32;
        if (render instanceof SkinPortRenderPlayer)
        {
            SkinPortModelPlayer m = ((SkinPortRenderPlayer) render).modelPlayer;
            model = m.smallArms ? "slim" : "wide";
            modelW = m.textureWidth;
            modelH = m.textureHeight;
        }
        else if (render instanceof RenderPlayer)
        {
            ModelBiped m = ((RenderPlayer) render).modelBipedMain;
            model = "vanilla";
            modelW = m.textureWidth;
            modelH = m.textureHeight;
        }
        else
        {
            model = render == null ? "no renderer" : render.getClass().getSimpleName();
        }
        ResourceLocation location = player.getLocationSkin();
        ITextureObject texture = mc.getTextureManager().getTexture(location);
        int[] size = textureSize(texture);
        skewed[0] = size[0] > 0 && modelW * size[1] != modelH * size[0];
        return String.format("%s %dx%d on %s %dx%d%s", origin(player, location, texture), size[0], size[1], model, modelW, modelH, skewed[0] ? " SKEWED" : "");
    }

    private static String origin(AbstractClientPlayer player, ResourceLocation location, ITextureObject texture)
    {
        if (texture instanceof CustomSkinTexture)
        {
            Object source = ClientProxy.resolvedSource(player);
            return "skinport:" + (source instanceof SkinData ? ((SkinData) source).describe() : String.valueOf(source));
        }
        if (texture instanceof ThreadDownloadImageData)
            return "vanilla-download" + (ClientProxy.vanillaSkinReady(location) ? "" : "(steve until done)");
        if (VANILLA_STEVE.equals(location))
            return "vanilla-steve";
        return String.valueOf(location);
    }

    /** Width and height of a loaded GL texture, or {0, 0} when it has none yet. */
    private static int[] textureSize(ITextureObject texture)
    {
        if (texture == null)
            return new int[] { 0, 0 };
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getGlTextureId());
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        return new int[] { w, h };
    }

    /** The texture's pixels as ARGB, top row first. */
    private static int[] readTexture(ITextureObject texture, int w, int h)
    {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getGlTextureId());
        IntBuffer buffer = BufferUtils.createIntBuffer(w * h);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        int[] argb = new int[w * h];
        buffer.get(argb);
        return argb;
    }

    // --- the script ----------------------------------------------------------------------------------

    private void buildSteps()
    {
        steps.add(new Step("open world", MENU_WAIT, () -> {
            // The first join is watched from its first frame: the recording is armed before the
            // world exists and starts the moment the player does
            startRecording("first join", () -> mc.thePlayer);
            // Third person from the front: the world shots taken while a join is watched show the
            // player's face, the way a player looking at themselves (or at the paper doll) sees it
            savedThirdPerson = mc.gameSettings.thirdPersonView;
            mc.gameSettings.thirdPersonView = 2;
        }, () -> {
            if (!startedWorld && mc.theWorld == null && mc.currentScreen != null)
            {
                startedWorld = true;
                launchWorld();
            }
            return mc.thePlayer != null && mc.theWorld != null && serverPlayer() != null;
        }, 20 * 60 * 10));
        step("settle world", 20, this::setUpWorld);
        stepUntil("first join recorded", 1, () -> {
        }, () -> finished != null, RECORDING_TIMEOUT);
        stepUntil("references", 1, () -> {
        }, () -> self.isDone() && offlineRef.isDone() && onlineRef.isDone() && unknownRef.isDone(), 20 * 60);
        probe("first join", 1, pt -> {
            reportReference(self);
            analyse(takeFinished(), mc.thePlayer, self, true);
            ownSkin = shot.take(mc.thePlayer);
        });

        probe("skin layers", 2, this::probeLayers);
        step("customisation screen", 2, this::testCustomizeScreen);
        step("customisation screen closed", 5, () -> check(mc.currentScreen == null, "the options screen closes", String.valueOf(mc.currentScreen)));

        if (RELOAD)
        {
            step("resource reload", 2, () -> {
                long start = System.currentTimeMillis();
                mc.refreshResources();
                note("resource reload took " + (System.currentTimeMillis() - start) + " ms");
            });
            probe("after the reload", 20, pt -> {
                ShotImage after = shot.take(mc.thePlayer);
                check(ownSkin != null && after.equals(ownSkin), "a resource reload keeps the skin", ownSkin == null ? "nothing to compare with" : String.format("%.1f%% of the player changed", 100 * ShotImage.mismatch(ownSkin, after, 0)));
                if (ownSkin != null && !after.equals(ownSkin))
                    after.save(file("sp-after-reload.png"));
            });
        }

        otherPlayer("offline-mode player " + OFFLINE_NAME, offlineRef, () -> new GameProfile(MojangReference.offlineId(OFFLINE_NAME), OFFLINE_NAME));
        otherPlayer("online-mode player " + ONLINE_NAME, onlineRef, onlineRef::onlineProfile);
        otherPlayer("player without an account", unknownRef, () -> new GameProfile(MojangReference.offlineId(unknownName), unknownName));

        if (REJOIN)
        {
            step("leave the world", 2, () -> {
                mc.theWorld.sendQuittingDisconnectingPacket();
                mc.loadWorld((WorldClient) null);
                mc.displayGuiScreen(new GuiMainMenu());
                leftWorldAt = System.currentTimeMillis();
                note("left the world, waiting " + REJOIN_WAIT_SECONDS + " s in the menu");
            });
            stepUntil("in the menu", 1, () -> {
            }, () -> System.currentTimeMillis() - leftWorldAt >= REJOIN_WAIT_SECONDS * 1000L, 20 * (REJOIN_WAIT_SECONDS + 60));
            stepUntil("join again", 1, () -> {
                leftWorldAt = 0L;
                startRecording("rejoin", () -> mc.thePlayer);
                launchWorld();
            }, () -> mc.thePlayer != null && mc.theWorld != null && serverPlayer() != null, 20 * 60 * 10);
            step("settle again", 20, this::dismissScreens);
            stepUntil("rejoin recorded", 1, () -> {
            }, () -> finished != null, RECORDING_TIMEOUT);
            probe("rejoin", 1, pt -> analyse(takeFinished(), mc.thePlayer, self, true));
        }

        step("finish", 20, this::finish);
    }

    private Recording takeFinished()
    {
        Recording r = finished;
        finished = null;
        return r;
    }

    private void launchWorld()
    {
        LOGGER.info("[autotest] launching world " + WORLD + " (menu: " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName()) + ")");
        WorldSettings settings = new WorldSettings(1234L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT);
        settings.enableCommands();
        mc.launchIntegratedServer(WORLD, WORLD, settings);
    }

    /** Spawns another player next to the local one and watches it from its first frame. */
    private void otherPlayer(String label, MojangReference ref, Supplier<GameProfile> profile)
    {
        step(label + ": spawn", 2, () -> {
            if (ref.error != null)
                note(label + ": Mojang reference failed: " + ref.error);
            GameProfile p = profile.get();
            dummy = new EntityOtherPlayerMP(mc.theWorld, p);
            dummy.setPositionAndRotation(mc.thePlayer.posX + 2, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ + 2, 0F, 0F);
            dummy.lastTickPosX = dummy.posX;
            dummy.lastTickPosY = dummy.posY;
            dummy.lastTickPosZ = dummy.posZ;
            // No cape in the shots: it loads on its own schedule and is not what is measured here
            SkinCustomization.Flags.put(Side.CLIENT, p.getId(), SkinCustomization.getDefaultFlags() & ~SkinCustomization.cape.getFlag());
            final EntityOtherPlayerMP subject = dummy;
            startRecording(label, () -> subject);
            mc.theWorld.addEntityToWorld(DUMMY_ID, dummy);
            note(label + ": spawned " + p.getName() + " [" + p.getId() + "] with " + p.getProperties().size() + " propert(ies)");
        });
        stepUntil(label + ": recorded", 1, () -> {
        }, () -> finished != null, RECORDING_TIMEOUT);
        probe(label, 1, pt -> {
            analyse(takeFinished(), dummy, ref, false);
            mc.theWorld.removeEntityFromWorld(DUMMY_ID);
            dummy = null;
        });
    }

    private void setUpWorld()
    {
        dismissScreens();
        savedGuiScale = mc.gameSettings.guiScale;
        savedFov = mc.gameSettings.fovSetting;
        savedPauseOnLostFocus = mc.gameSettings.pauseOnLostFocus;
        mc.gameSettings.pauseOnLostFocus = false;
        mc.gameSettings.hideGUI = false;
        mc.gameSettings.showDebugInfo = false;
        mc.gameSettings.guiScale = 2;
        mc.gameSettings.fovSetting = 70F;
        resize(WIDTH, HEIGHT);

        MinecraftServer server = MinecraftServer.getServer();
        if (server != null)
        {
            server.func_147139_a(EnumDifficulty.PEACEFUL);
            for (WorldServer world : server.worldServers)
            {
                world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                world.setWorldTime(6000L);
            }
        }
        mc.theWorld.setWorldTime(6000L);
        EntityPlayerMP player = serverPlayer();
        if (player != null)
        {
            player.setGameType(WorldSettings.GameType.CREATIVE);
            player.inventory.clearInventory(null, -1);
            player.inventoryContainer.detectAndSendChanges();
            player.capabilities.isFlying = false;
            player.sendPlayerAbilities();
            player.setPositionAndUpdate(0.5, player.worldObj.getTopSolidOrLiquidBlock(0, 0) + 0.1, 0.5);
        }
        mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0F;
        mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0F;

        // The shots leave the cape out (it loads on its own schedule); the player's own setting is
        // put back at the end
        savedFlags = SkinCustomization.ClientFlags;
        savedOptionsFile = readFile(new File(mc.mcDataDir, "options_skinport.txt"));
        SkinCustomization.ClientFlags = SkinCustomization.getDefaultFlags() & ~SkinCustomization.cape.getFlag();
        note("world ready; renderer " + RenderManager.instance.getEntityRenderObject(mc.thePlayer).getClass().getName() + ", framebuffers " + (PlayerShot.available() ? "on" : "OFF"));
        check(PlayerShot.available(), "framebuffers available for the offscreen shots", "");
    }

    private void dismissScreens()
    {
        if (mc.currentScreen != null)
        {
            // Packs open things on first join (GT New Horizons pops the quest book); get them out of the way
            note("dismissing " + mc.currentScreen.getClass().getName());
            mc.displayGuiScreen(null);
        }
    }

    // --- analysis ------------------------------------------------------------------------------------

    private void reportReference(MojangReference ref)
    {
        if (ref.error != null)
            note("reference for " + ref.name + " failed: " + ref.error);
        else if (ref.missing)
            note("reference: Mojang has no account " + ref.name);
        else
            note("reference: " + ref.name + " is " + ref.id + ", model " + ref.model + ", skin " + ref.skinUrl + (ref.capeUrl != null ? ", cape " + ref.capeUrl : ", no cape"));
        check(ref.error == null, "Mojang answers for " + ref.name, ref.error);
    }

    /**
     * Reads a watched join back: every frame is compared with the one the player settled on, and
     * the settled one with what Mojang says the account looks like.
     *
     * @param own for the local player nothing but the own skin may ever be drawn; another player may
     *            show one stand-in (a default skin, drawn properly) while theirs downloads.
     */
    private void analyse(Recording rec, AbstractClientPlayer player, MojangReference ref, boolean own)
    {
        if (rec == null || rec.samples.isEmpty())
        {
            check(false, (rec == null ? "?" : rec.name) + ": frames were recorded", "none");
            return;
        }
        String label = rec.name;
        String slug = slug(label);
        List<Recording.Segment> segments = rec.segments();
        note(String.format("%s: %d frames over %d ms, %d distinct state(s):", label, rec.samples.size(), rec.last().ms, segments.size()));
        for (Recording.Segment s : segments)
            note(label + ":   " + s.describe());
        check(!rec.timedOut(), label + ": the player settles", rec.timedOut() ? "still changing after " + rec.maxMs + " ms" : "");

        long settledHash = rec.last().hash;
        rec.saveStrip(file("sp-" + slug + "-timeline.png"), settledHash);
        int n = 0;
        for (ShotImage image : rec.images.values())
            image.save(file(String.format("sp-%s-picture-%02d.png", slug, n++)));

        // The picture it settled on has to be the account's own skin, on the account's own model
        boolean right = checkSettled(label, player, ref);

        int skewed = 0;
        String firstSkewed = null;
        for (Recording.Sample s : rec.samples)
        {
            if (s.skewed)
            {
                skewed++;
                if (firstSkewed == null)
                    firstSkewed = s.state + " at " + s.ms + " ms";
            }
        }
        check(skewed == 0, label + ": never a 64x32 texture stretched over the 64x64 model", skewed == 0 ? "" : skewed + " frame(s), first: " + firstSkewed);

        int firstRight = -1;
        for (int i = 0; i < rec.samples.size(); i++)
        {
            if (rec.samples.get(i).hash == settledHash)
            {
                firstRight = i;
                break;
            }
        }
        int otherBefore = 0, emptyBefore = 0, changedAfter = 0;
        Set<Long> standIns = new LinkedHashSet<>();
        Set<String> standInStates = new LinkedHashSet<>();
        for (int i = 0; i < rec.samples.size(); i++)
        {
            Recording.Sample s = rec.samples.get(i);
            if (i < firstRight)
            {
                if (s.empty)
                {
                    emptyBefore++;
                }
                else
                {
                    otherBefore++;
                    standIns.add(s.hash);
                    standInStates.add(s.state);
                }
            }
            else if (s.hash != settledHash)
            {
                changedAfter++;
            }
        }
        Recording.Sample first = rec.samples.get(firstRight);
        note(String.format("%s: settled picture from %d ms (frame %d); before it %d frame(s) of something else, %d with nothing drawn", label, first.ms, first.frame, otherBefore, emptyBefore));
        if (right)
        {
            if (own)
            {
                check(otherBefore == 0, label + ": the player's own skin from the very first frame", otherBefore == 0 ? "" : otherBefore + " frame(s) over " + first.ms + " ms showed " + String.join(" | ", standInStates));
            }
            else
            {
                check(standIns.size() <= 1, label + ": at most one stand-in while the skin loads", standIns.size() + " different picture(s): " + String.join(" | ", standInStates));
                boolean properStandIn = true;
                for (String state : standInStates)
                    properStandIn &= state.startsWith("skinport:default-");
                check(properStandIn, label + ": the stand-in is a default skin drawn by SkinPort", String.join(" | ", standInStates));
                check(first.ms < 15000L, label + ": the skin arrives within 15 s", first.ms + " ms");
            }
        }
        check(changedAfter == 0, label + ": once settled, the picture never changes back", changedAfter == 0 ? "" : changedAfter + " frame(s) differ after it first showed");
    }

    /**
     * The texture SkinPort settled on is pixel for pixel the account's skin (as SkinPort converts
     * it), and the arms are the account's model - by renderer and by the width actually drawn.
     */
    private boolean checkSettled(String label, AbstractClientPlayer player, MojangReference ref)
    {
        ResourceLocation location = player.getLocationSkin();
        ITextureObject texture = mc.getTextureManager().getTexture(location);
        Render render = RenderManager.instance.getEntityRenderObject(player);
        boolean slim = render instanceof SkinPortRenderPlayer && ((SkinPortRenderPlayer) render).modelPlayer.smallArms;
        ShotImage settled = shot.take(player);
        double width = shoulderWidth(settled);
        note(String.format("%s: settled on %s, %s arms drawn %.2f skin pixels across the shoulders", label, describe(player, new boolean[1]), slim ? "slim" : "wide", width));

        BufferedImage expected;
        String expectedModel;
        if (ref.error != null || !ref.isDone())
        {
            check(false, label + ": Mojang reference available", ref.error);
            return false;
        }
        if (ref.missing)
        {
            // No account: 1.8's rule picks Alex for odd UUID hashes, Steve for even
            boolean alex = (player.getUniqueID().hashCode() & 1) == 1;
            expected = resource(alex ? "/DefaultAlex.png" : "/DefaultSteve.png");
            if (expected == null)
                note(label + ": could not read the default skin from the jar");
            expectedModel = alex ? "slim" : "default";
        }
        else
        {
            expected = ref.skin == null ? null : new LegacyConversion().convert(ref.skin);
            expectedModel = ref.model;
        }
        boolean ok = true;
        if (expected == null)
        {
            check(false, label + ": expected skin image", "none");
            return false;
        }
        boolean ours = texture instanceof CustomSkinTexture;
        check(ours, label + ": the settled texture is SkinPort's own", String.valueOf(texture == null ? null : texture.getClass().getSimpleName()));
        ok &= ours;
        if (ours)
        {
            int[] size = textureSize(texture);
            int[] actual = readTexture(texture, size[0], size[1]);
            int w = expected.getWidth(), h = expected.getHeight();
            int differ = 0;
            if (size[0] != w || size[1] != h)
            {
                differ = w * h;
            }
            else
            {
                int[] want = expected.getRGB(0, 0, w, h, null, 0, w);
                for (int i = 0; i < want.length; i++)
                {
                    int a = want[i] >>> 24, b = actual[i] >>> 24;
                    if (a != b || (a != 0 && (want[i] & 0xFFFFFF) != (actual[i] & 0xFFFFFF)))
                        differ++;
                }
            }
            check(differ == 0, label + ": the texture is " + (ref.missing ? "the default skin for this id" : ref.name + "'s skin from Mojang"), differ == 0 ? size[0] + "x" + size[1] : differ + " of " + (w * h) + " pixels differ (texture " + size[0] + "x" + size[1] + ", expected " + w + "x" + h + ")");
            ok &= differ == 0;
        }
        boolean modelRight = slim == "slim".equals(expectedModel);
        check(modelRight, label + ": the arms are " + expectedModel, slim ? "slim renderer" : "wide renderer");
        ok &= modelRight;
        // By pixels: slim arms are 3 wide, so the shoulders measure 14 skin pixels, wide 16 (half a
        // pixel more each side where a sleeve is drawn)
        boolean widthRight = "slim".equals(expectedModel) ? width > 13.0 && width < 15.0 : width > 15.2 && width < 17.2;
        check(widthRight, label + ": the drawn arms are " + expectedModel, String.format("%.2f skin pixels across the shoulders", width));
        return ok && widthRight;
    }

    /** Width of the front view three skin pixels below the shoulders, in skin pixels. */
    private static double shoulderWidth(ShotImage image)
    {
        int y = Math.round(PlayerShot.FEET_Y - 21 * PlayerShot.PX) - 1;
        return image.span(y, 0, PlayerShot.WIDTH / 2) / PlayerShot.PX;
    }

    /** A default skin as the default provider uploads it: the file as it is, no conversion. */
    private static BufferedImage resource(String path)
    {
        try (InputStream in = AutoTest.class.getResourceAsStream(path))
        {
            return ImageIO.read(in);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // --- layers and the customisation screen -------------------------------------------------------

    /** Hiding a layer changes the picture exactly when the skin has something drawn on that layer. */
    private void probeLayers(float pt)
    {
        if (!self.hasSkin())
        {
            note("layers skipped: no reference skin");
            return;
        }
        BufferedImage skin = new LegacyConversion().convert(self.skin);
        boolean slim = "slim".equals(self.model);
        int base = SkinCustomization.ClientFlags;
        try
        {
            ShotImage all = shot.take(mc.thePlayer);
            for (SkinCustomization part : LAYERS)
            {
                SkinCustomization.ClientFlags = base & ~part.getFlag();
                ShotImage without = shot.take(mc.thePlayer);
                boolean visible = layerVisible(skin, part, slim);
                boolean changed = !without.equals(all);
                check(changed == visible, "layer " + part.name() + (visible ? " can be hidden" : " is empty on this skin and hiding it changes nothing"), String.format("%.2f%% of the player changed", 100 * ShotImage.mismatch(all, without, 0)));
            }
            SkinCustomization.ClientFlags = base & ~SkinCustomization.of(LAYERS);
            shot.take(mc.thePlayer).save(file("sp-layers-none.png"));
            all.save(file("sp-layers-all.png"));
        }
        finally
        {
            SkinCustomization.ClientFlags = base;
        }
    }

    /** Whether the front or back face of a layer has any pixel the alpha test lets through. */
    private static boolean layerVisible(BufferedImage skin, SkinCustomization part, boolean slim)
    {
        int arm = slim ? 3 : 4;
        int[][] faces;
        switch (part)
        {
            case hat:
                faces = new int[][] { { 40, 8, 48, 16 }, { 56, 8, 64, 16 } };
                break;
            case jacket:
                faces = new int[][] { { 20, 36, 28, 48 }, { 32, 36, 40, 48 } };
                break;
            case right_sleeve:
                faces = new int[][] { { 44, 36, 44 + arm, 48 }, { 48 + arm, 36, 48 + 2 * arm, 48 } };
                break;
            case left_sleeve:
                faces = new int[][] { { 52, 52, 52 + arm, 64 }, { 56 + arm, 52, 56 + 2 * arm, 64 } };
                break;
            case right_pants_leg:
                faces = new int[][] { { 4, 36, 8, 48 }, { 12, 36, 16, 48 } };
                break;
            case left_pants_leg:
                faces = new int[][] { { 4, 52, 8, 64 }, { 12, 52, 16, 64 } };
                break;
            default:
                return false;
        }
        for (int[] f : faces)
            for (int y = f[1]; y < f[3]; y++)
                for (int x = f[0]; x < f[2]; x++)
                    if ((skin.getRGB(x, y) >>> 24) > 26) // the alpha test drops anything at 0.1 or below
                        return true;
        return false;
    }

    private void testCustomizeScreen()
    {
        int before = SkinCustomization.ClientFlags;
        File options = new File(mc.mcDataDir, "options_skinport.txt");
        GuiOptions screen = new GuiOptions(null, mc.gameSettings);
        mc.displayGuiScreen(screen);
        optionsWritten = true;
        GuiButton open = button(screen, 110);
        check(open != null, "the options screen has SkinPort's skin customisation button", "");
        if (open == null)
        {
            mc.displayGuiScreen(null);
            return;
        }
        click(screen, open);
        check(mc.currentScreen instanceof SkinPortGuiCustomizeSkin, "the button opens the skin customisation screen", String.valueOf(mc.currentScreen));
        if (!(mc.currentScreen instanceof SkinPortGuiCustomizeSkin))
        {
            mc.displayGuiScreen(null);
            return;
        }
        GuiScreen custom = mc.currentScreen;
        GuiButton hat = button(custom, SkinCustomization.hat.ordinal());
        click(custom, hat);
        check(!SkinCustomization.contains(SkinCustomization.ClientFlags, SkinCustomization.hat), "clicking Hat hides the hat", "flags " + SkinCustomization.ClientFlags);
        String saved = readFile(options);
        check(saved != null && saved.contains("clientFlags:" + SkinCustomization.ClientFlags), "the change is written to options_skinport.txt", saved);
        click(custom, hat);
        check(SkinCustomization.ClientFlags == before, "clicking it again brings the hat back", "flags " + SkinCustomization.ClientFlags);
        click(custom, button(custom, 200));
        check(mc.currentScreen == screen, "Done goes back to the options screen", String.valueOf(mc.currentScreen));
        mc.displayGuiScreen(null);
    }

    @SuppressWarnings("unchecked")
    private static GuiButton button(GuiScreen screen, int id)
    {
        List<GuiButton> buttons = (List<GuiButton>) ReflectionHelper.getPrivateValue(GuiScreen.class, screen, "buttonList", "field_146292_n");
        for (GuiButton b : buttons)
            if (b.id == id)
                return b;
        return null;
    }

    /** A left click in the middle of the button, the way the screen itself receives one. */
    private static void click(GuiScreen screen, GuiButton button)
    {
        if (button == null)
            return;
        try
        {
            Method mouseClicked = ReflectionHelper.findMethod(GuiScreen.class, screen, new String[] { "mouseClicked", "func_73864_a" }, int.class, int.class, int.class);
            mouseClicked.invoke(screen, button.xPosition + button.width / 2, button.yPosition + 10, 0);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    // --- bits ----------------------------------------------------------------------------------------

    private EntityPlayerMP serverPlayer()
    {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null)
            return null;
        List<?> list = server.getConfigurationManager().playerEntityList;
        return list.isEmpty() ? null : (EntityPlayerMP) list.get(0);
    }

    private void resize(int width, int height)
    {
        try
        {
            Display.setDisplayMode(new DisplayMode(width, height));
            mc.resize(width, height);
        }
        catch (Exception e)
        {
            LOGGER.warn("[autotest] could not resize to " + width + "x" + height, e);
        }
    }

    private void screenshot(String name)
    {
        try
        {
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        }
        catch (Throwable t)
        {
            LOGGER.warn("[autotest] screenshot failed", t);
        }
    }

    private File file(String name)
    {
        return new File(mc.mcDataDir, "screenshots/" + name);
    }

    private static String slug(String label)
    {
        return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static String readFile(File file)
    {
        try
        {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // --- the end ---------------------------------------------------------------------------------------

    private void finish()
    {
        String summary = "RESULT passed=" + passed + " failed=" + failed;
        LOGGER.info("[autotest] " + summary);
        writeReport(summary);
        shot.dispose();
        // The player's own settings back, and the options file as it was
        if (savedGuiScale >= 0)
        {
            SkinCustomization.ClientFlags = savedFlags;
            if (savedOptionsFile != null)
            {
                try
                {
                    Files.write(new File(mc.mcDataDir, "options_skinport.txt").toPath(), savedOptionsFile.getBytes(StandardCharsets.UTF_8));
                }
                catch (Exception e)
                {
                    ForgeSkinPort.saveOptions();
                }
            }
            mc.gameSettings.guiScale = savedGuiScale;
            mc.gameSettings.thirdPersonView = savedThirdPerson;
            mc.gameSettings.fovSetting = savedFov;
            mc.gameSettings.pauseOnLostFocus = savedPauseOnLostFocus;
            mc.gameSettings.hideGUI = false;
            if (optionsWritten)
                mc.gameSettings.saveOptions();
        }
        LOGGER.info("[autotest] done; the game quits after " + IDLE_EXIT_MS / 1000 + " s without input");
        idleWatch = true;
        lastActivity = System.currentTimeMillis();
        lastFingerprint = activityFingerprint();
    }

    /** Everything a person at the keyboard changes: where the player is and looks, screens, keys, mouse. */
    private String activityFingerprint()
    {
        StringBuilder sb = new StringBuilder();
        if (mc.thePlayer != null)
        {
            sb.append(Math.round(mc.thePlayer.rotationYaw * 10)).append(',').append(Math.round(mc.thePlayer.rotationPitch * 10)).append(',');
            sb.append(Math.round(mc.thePlayer.posX * 10)).append(',').append(Math.round(mc.thePlayer.posY * 10)).append(',').append(Math.round(mc.thePlayer.posZ * 10)).append(';');
        }
        sb.append(mc.currentScreen == null ? "-" : mc.currentScreen.getClass().getName()).append(';');
        for (int key = 1; key < Keyboard.KEYBOARD_SIZE; key++)
            if (Keyboard.isKeyDown(key))
                sb.append('k').append(key);
        for (int button = 0; button < Mouse.getButtonCount(); button++)
            if (Mouse.isButtonDown(button))
                sb.append('b').append(button);
        sb.append(';').append(Mouse.getX()).append(',').append(Mouse.getY());
        return sb.toString();
    }

    /** Quits once the game has been left alone for {@link #IDLE_EXIT_MS}; any input starts the minute again. */
    private void watchIdle()
    {
        long now = System.currentTimeMillis();
        String fingerprint = activityFingerprint();
        if (!fingerprint.equals(lastFingerprint))
        {
            lastFingerprint = fingerprint;
            lastActivity = now;
        }
        else if (now - lastActivity >= IDLE_EXIT_MS)
        {
            idleWatch = false;
            LOGGER.info("[autotest] left alone for " + IDLE_EXIT_MS / 1000 + " s, shutting down");
            mc.shutdown();
        }
    }

}
