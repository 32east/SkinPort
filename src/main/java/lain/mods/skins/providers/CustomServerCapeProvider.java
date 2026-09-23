package lain.mods.skins.providers;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lain.lib.SharedPool;
import lain.mods.skins.api.interfaces.IPlayerProfile;
import lain.mods.skins.api.interfaces.ISkin;
import lain.mods.skins.api.interfaces.ISkinProvider;
import lain.mods.skins.impl.Shared;
import lain.mods.skins.impl.SkinData;

public class CustomServerCapeProvider implements ISkinProvider
{

    private Function<ByteBuffer, ByteBuffer> _filter;
    private String _host;

    @Override
    public ISkin getSkin(IPlayerProfile profile)
    {
        SkinData skin = new SkinData("customserver-cape");
        if (_filter != null)
            skin.setSkinFilter(_filter);
        SharedPool.execute(() -> {
            java.util.concurrent.CompletableFuture<?> pending;
            if (Shared.isOfflinePlayer(profile.getPlayerID(), profile.getPlayerName()))
            {
                pending = Shared.downloadSkin(String.format("%s/capes/%s", _host, profile.getPlayerName()), Runnable::run).thenApply(opt -> opt.orElse(null)).thenAccept(data -> {
                    if (data != null && SkinData.validateData(data))
                        skin.put(data, "cape");
                });
            }
            else
            {
                pending = Shared.downloadSkin(String.format("%s/capes/%s", _host, profile.getPlayerID()), Runnable::run).handle((r, t) -> {
                    if (r != null && r.isPresent())
                        return CompletableFuture.completedFuture(r);
                    return Shared.downloadSkin(String.format("%s/capes/%s", _host, profile.getPlayerName()), Runnable::run);
                }).thenCompose(Function.identity()).thenApply(opt -> opt.orElse(null)).thenAccept(data -> {
                    if (data != null && SkinData.validateData(data))
                        skin.put(data, "cape");
                });
            }
            pending.whenComplete((r, t) -> skin.markSettled());
        });
        return skin;
    }

    public CustomServerCapeProvider setHost(String host)
    {
        _host = host;
        return this;
    }

    public CustomServerCapeProvider withFilter(Function<ByteBuffer, ByteBuffer> filter)
    {
        _filter = filter;
        return this;
    }

}
