package com.mtrmap.fabric;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.MtrMapPlatform;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Fabric 平台实现。只有 {@link #sendAvatarToServer(UUID, byte[])} 会触碰客户端专属类，
 * 而该方法只会由客户端 tick 调用，故专用服务器上不会加载它。
 */
public class FabricPlatformImpl implements MtrMapPlatform {

    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public void sendAvatarToServer(UUID uuid, byte[] png) {
        //? if >=1.21.1 {
        /*ClientPlayNetworking.send(new AvatarPayload(uuid, png));
        *///?} else {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUUID(uuid);
        buf.writeByteArray(png);
        ClientPlayNetworking.send(MtrMapCommon.AVATAR_CHANNEL, buf);
        //?}
    }

    @Override
    public void sendMapPort(ServerPlayer player, int port) {
        //? if >=1.21.1 {
        /*ServerPlayNetworking.send(player, new MapPortPayload(port));
        *///?} else {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeInt(port);
        ServerPlayNetworking.send(player, MtrMapCommon.PORT_CHANNEL, buf);
        //?}
    }
}
