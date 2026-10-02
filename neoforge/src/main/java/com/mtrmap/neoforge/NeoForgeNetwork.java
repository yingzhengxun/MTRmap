package com.mtrmap.neoforge;

import com.mtrmap.MtrMapCommon;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/**
 * NeoForge 网络通道：承载客户端推送头像的载荷。
 * 收到后转交给与加载器无关的 {@link MtrMapCommon#onAvatarReceived}。
 *
 * <p>与 Forge 版（SimpleChannel）的差异：这里用 NeoForge 1.21.1 的自定义载荷 API，
 * 注册事件 {@link RegisterPayloadHandlersEvent} 是 mod 总线事件，故由 mod 构造器传入的
 * {@link IEventBus} 挂监听（{@link #register(IEventBus)}）。
 */
public final class NeoForgeNetwork {

    /** 网络协议版本：与 Forge 版保持一致，便于两端比对。 */
    private static final String PROTOCOL_VERSION = "1";

    private NeoForgeNetwork() {
    }

    /** 注册载荷处理器（在 mod 构造期拿到 mod 总线后调用一次）。 */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(NeoForgeNetwork::onRegisterPayloadHandlers);
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(
                AvatarPayload.TYPE,
                AvatarPayload.STREAM_CODEC,
                (payload, context) -> MtrMapCommon.onAvatarReceived(
                        MtrMapCommon.getCurrentServer(), payload.uuid(), payload.png()));
    }

    /** 客户端把头像推给服务端。 */
    public static void sendAvatarToServer(UUID uuid, byte[] png) {
        PacketDistributor.sendToServer(new AvatarPayload(uuid, png));
    }
}
