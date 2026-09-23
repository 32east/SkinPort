package lain.mods.skins.providers;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import lain.lib.SharedPool;
import lain.mods.skins.api.interfaces.IPlayerProfile;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.api.interfaces.ISkinProvider;
import lain.mods.skins.impl.Shared;
import lain.mods.skins.impl.SkinData;
import lain.mods.skins.impl.forge.MinecraftUtils;

public class MojangCapeProvider implements ISkinProvider
{

    private Function<ByteBuffer, ByteBuffer> _filter;

    @Override
    public ISkin getSkin(IPlayerProfile profile)
    {
        SkinData skin = new SkinData("mojang-cape");
        if (_filter != null)
            skin.setSkinFilter(_filter);
        SharedPool.execute(() -> {
            java.util.concurrent.CompletableFuture<?> pending = null;
            try
            {
                if (!Shared.isOfflinePlayer(profile.getPlayerID(), profile.getPlayerName()))
                {
                    Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = MinecraftUtils.getSessionService().getTextures((GameProfile) profile.getOriginal(), false);
                    if (textures != null && textures.containsKey(MinecraftProfileTexture.Type.CAPE))
                    {
                        MinecraftProfileTexture tex = textures.get(MinecraftProfileTexture.Type.CAPE);
                        pending = Shared.downloadSkin(tex.getUrl(), Runnable::run).thenApply(opt -> opt.orElse(null)).thenAccept(data -> {
                            if (data != null && SkinData.validateData(data))
                                skin.put(data, "cape");
                        });
                    }
                }
            }
            finally
            {
                if (pending != null)
                    pending.whenComplete((r, t) -> skin.markSettled());
                else
                    skin.markSettled();
            }
        });
        return skin;
    }

    public MojangCapeProvider withFilter(Function<ByteBuffer, ByteBuffer> filter)
    {
        _filter = filter;
        return this;
    }

}
