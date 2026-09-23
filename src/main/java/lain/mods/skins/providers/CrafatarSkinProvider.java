package lain.mods.skins.providers;

import java.nio.ByteBuffer;
import java.util.function.Function;
import com.mojang.authlib.GameProfile;
import lain.lib.SharedPool;
import lain.mods.skins.api.interfaces.IPlayerProfile;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.api.interfaces.ISkinProvider;
import lain.mods.skins.impl.Shared;
import lain.mods.skins.impl.SkinData;
import lain.mods.skins.impl.SkinLog;

public class CrafatarSkinProvider implements ISkinProvider
{

    private Function<ByteBuffer, ByteBuffer> _filter;

    @Override
    public ISkin getSkin(IPlayerProfile profile)
    {
        SkinData skin = new SkinData("crafatar");
        if (_filter != null)
            skin.setSkinFilter(_filter);
        SharedPool.execute(() -> {
            java.util.concurrent.CompletableFuture<?> pending = null;
            try
            {
                if (!Shared.isOfflinePlayer(profile.getPlayerID(), profile.getPlayerName()))
                {
                    // Crafatar mirrors the Mojang skin, so Mojang's own model metadata describes it
                    // exactly. Preferring it over the pixel guess keeps both providers on the same
                    // model, so whichever one wins the race the arms do not change width.
                    String hint = Shared.getModelHint((GameProfile) profile.getOriginal());
                    pending = Shared.downloadSkin(String.format("https://crafatar.com/skins/%s", profile.getPlayerID()), Runnable::run).thenApply(opt -> opt.orElse(null)).thenAccept(data -> {
                        if (data != null && SkinData.validateData(data))
                        {
                            String type = SkinData.judgeSkinType(data, hint);
                            skin.put(data, type);
                            SkinLog.debug("crafatar %s: %s got %d bytes, type=%s (hint=%s)", profile.getPlayerName(), SkinLog.id(skin), data.length, type, hint);
                        }
                        else
                        {
                            SkinLog.debug("crafatar %s: %s no usable image", profile.getPlayerName(), SkinLog.id(skin));
                        }
                    });
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

    public CrafatarSkinProvider withFilter(Function<ByteBuffer, ByteBuffer> filter)
    {
        _filter = filter;
        return this;
    }

}
