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

    /** 请求里字符串（path / POST 体）的长度上限，与接收端一致 */
    private static final int MAX_TEXT = 1 << 20;

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

    @Override
    public void sendMapRequest(int requestId, String path, String body) {
        //? if >=1.21.1 {
        /*ClientPlayNetworking.send(new MapRequestPayload(requestId, path, body));
        *///?} else {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(requestId);
        buf.writeUtf(path, MAX_TEXT);
        buf.writeUtf(body, MAX_TEXT);
        ClientPlayNetworking.send(MtrMapCommon.MAP_REQUEST_CHANNEL, buf);
        //?}
    }

    @Override
    public void sendMapData(ServerPlayer player, int requestId, int index, int total, byte[] chunk) {
        //? if >=1.21.1 {
        /*ServerPlayNetworking.send(player, new MapDataPayload(requestId, index, total, chunk));
        *///?} else {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(requestId);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(chunk);
        ServerPlayNetworking.send(player, MtrMapCommon.MAP_DATA_CHANNEL, buf);
        //?}
    }
}
