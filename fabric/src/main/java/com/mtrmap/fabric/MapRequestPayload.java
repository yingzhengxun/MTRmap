package com.mtrmap.fabric;

//? if >=1.21.1 {
/*import com.mtrmap.MtrMapCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// 客户端 -> 服务端的地图数据请求：path（可带查询串）+ POST 体。
// 服务端处理完把结果分块回传（MapDataPayload）。
// 注意：本文件整体处于 Stonecutter 的注释分支里，只能写 // 行注释。
public record MapRequestPayload(int requestId, String path, String body) implements CustomPacketPayload {

	// 请求体可能是一条完整的导航任务 JSON，长度上限给宽一点
	public static final int MAX_TEXT = 1 << 20;

	public static final CustomPacketPayload.Type<MapRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(MtrMapCommon.MAP_REQUEST_CHANNEL);

	public static final StreamCodec<RegistryFriendlyByteBuf, MapRequestPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeVarInt(payload.requestId());
				buf.writeUtf(payload.path(), MAX_TEXT);
				buf.writeUtf(payload.body(), MAX_TEXT);
			},
			buf -> new MapRequestPayload(buf.readVarInt(), buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT)));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
*///?}
