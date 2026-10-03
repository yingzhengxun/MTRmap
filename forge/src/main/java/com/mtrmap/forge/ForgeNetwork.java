package com.mtrmap.forge;

import com.mtrmap.MtrMapCommon;
// Forge 的网络包路径分三代：
//   1.18.2+  net.minecraftforge.network
//   1.17.1   net.minecraftforge.fmllegacy.network（1.19 之前的过渡包）
//   1.16.5   net.minecraftforge.fml.network
//? if >=1.18.2 {
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
//?} else if >=1.17 {
/*import net.minecraftforge.fmllegacy.network.NetworkDirection;
import net.minecraftforge.fmllegacy.network.NetworkRegistry;
import net.minecraftforge.fmllegacy.network.simple.SimpleChannel;
*///?} else {
/*import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;
*///?}

import java.util.UUID;

/**
 * Forge 网络通道（SimpleChannel）：承载客户端推送头像的消息。
 * 收到后转交给与加载器无关的 {@link MtrMapCommon#onAvatarReceived}。
 */
public final class ForgeNetwork {

    private static final String PROTOCOL_VERSION = "1";

    //? if >=1.18.2 {
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(MtrMapCommon.AVATAR_CHANNEL)
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();
    //?} else {
    /*// 1.17.1 与 1.16.5 都还没有 ChannelBuilder，用工厂方法直接建通道
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            MtrMapCommon.AVATAR_CHANNEL,
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);
    *///?}

    private ForgeNetwork() {
    }

    /** 注册消息处理器（在 mod 构造期调用一次）。 */
    public static void register() {
        CHANNEL.messageBuilder(AvatarMessage.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AvatarMessage::encode)
                .decoder(AvatarMessage::decode)
                // consumerMainThread 是 1.19 才加的；1.16.5 与 1.18.2 只有 consumer（在网络线程回调）
                //? if >=1.19 {
                .consumerMainThread((msg, ctx) -> {
                    MtrMapCommon.onAvatarReceived(MtrMapCommon.getCurrentServer(), msg.uuid(), msg.png());
                    ctx.get().setPacketHandled(true);
                })
                //?} else {
                /*.consumer((msg, ctx) -> {
                    MtrMapCommon.onAvatarReceived(MtrMapCommon.getCurrentServer(), msg.uuid(), msg.png());
                    ctx.get().setPacketHandled(true);
                })
                *///?}
                .add();
    }

    /** 客户端把头像推给服务端。 */
    public static void sendAvatarToServer(UUID uuid, byte[] png) {
        CHANNEL.sendToServer(new AvatarMessage(uuid, png));
    }
}
