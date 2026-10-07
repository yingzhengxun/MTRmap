package com.mtrmap.fabric;

//? if >=1.21.1 {
/*import com.mtrmap.MtrMapCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// 服务端 -> 客户端的地图数据分块：整份 JSON 按 MAP_CHUNK_SIZE 切片后逐块发来，
// 客户端按 requestId 归档、下标拼回。线网大时一份 JSON 几百 KB，只能分块。
// 注意：本文件整体处于 Stonecutter 的注释分支里，只能写 // 行注释。
public record MapDataPayload(int requestId, int index, int total, byte[] chunk) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<MapDataPayload> TYPE =
			new CustomPacketPayload.Type<>(MtrMapCommon.MAP_DATA_CHANNEL);

	public static final StreamCodec<RegistryFriendlyByteBuf, MapDataPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeVarInt(payload.requestId());
				buf.writeVarInt(payload.index());
				buf.writeVarInt(payload.total());
				buf.writeByteArray(payload.chunk());
			},
			buf -> new MapDataPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
*///?}
