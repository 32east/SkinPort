package lain.mods.skins.providers;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Function;
import lain.lib.SharedPool;
import lain.mods.skins.api.interfaces.IPlayerProfile;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.api.interfaces.ISkinProvider;
import lain.mods.skins.impl.Shared;
import lain.mods.skins.impl.SkinData;

public class CrafatarCapeProvider implements ISkinProvider
{

    private Function<ByteBuffer, ByteBuffer> _filter;

    @Override
    public ISkin getSkin(IPlayerProfile profile)
    {
        SkinData skin = new SkinData("crafatar-cape");
        if (_filter != null)
            skin.setSkinFilter(_filter);
        SharedPool.execute(() -> {
            java.util.concurrent.CompletableFuture<?> pending = null;
            try
            {
                if (!Shared.isOfflinePlayer(profile.getPlayerID(), profile.getPlayerName()))
                {
                    pending = Shared.downloadSkin(String.format("https://crafatar.com/capes/%s", profile.getPlayerID()), Runnable::run).thenApply(opt -> opt.orElse(null)).thenAccept(data -> {
                        if (data != null && SkinData.validateData(data))
                            skin.put(data, "cape");
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

    public CrafatarCapeProvider withFilter(Function<ByteBuffer, ByteBuffer> filter)
    {
        _filter = filter;
        return this;
    }

}
