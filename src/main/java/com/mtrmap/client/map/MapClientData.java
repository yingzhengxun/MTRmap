package com.mtrmap.client.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtrmap.server.MapDataCollector;
import com.mtrmap.server.MtrNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.UUID;

/**
 * 客户端自采集的地图数据：服务端没装本模组时，游戏内地图改用它。
 *
 * <p>MTR 是双端模组，客户端本身就保存了一份从服务器同步下来的线网缓存
 * （3.x 在 {@code mtr.client.ClientData}，4.x 在 {@code MinecraftClientData}），
 * 由 {@link MtrNetwork} 统一读成与 {@code /api/data} 相同的 JSON 结构，
 * 因此 {@link MapModel} 的解析逻辑完全复用，不需要两套渲染。
 *
 * <p>网页地图、行程记录、导航同步这些依赖服务端 HTTP 服务的功能在这种模式下不可用。
 */
public final class MapClientData {

	private MapClientData() {
	}

	/** 客户端线程调用：把 MTR 客户端缓存刷新成快照，之后轮询线程只读快照 */
	public static void refresh() {
		MtrNetwork.captureClient();
	}

	/** 轮询线程调用：组装与 /api/data 相同的 JSON，另补玩家与列车 */
	public static JsonObject collect() {
		JsonObject data = MapDataCollector.collect(null, false);
		data.add("players", players());
		data.add("trains", trains());
		data.addProperty("clientOnly", true);
		return data;
	}

	/** 客户端能看到的同维度玩家，字段与服务端 /api/players 一致 */
	public static JsonArray players() {
		JsonArray array = new JsonArray();
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return array;
		}
		for (Player player : client.level.players()) {
			JsonObject obj = new JsonObject();
			obj.addProperty("uuid", player.getUUID().toString());
			obj.addProperty("name", player.getName().getString());
			obj.addProperty("x", player.getX());
			obj.addProperty("y", player.getY());
			obj.addProperty("z", player.getZ());
			obj.addProperty("yaw", player.getYRot());
			array.add(obj);
		}
		return array;
	}

	/** 列车：字段与服务端 TrainCollector 一致，乘客名用客户端在线玩家解析 */
	private static JsonArray trains() {
		JsonArray array = new JsonArray();
		Minecraft client = Minecraft.getInstance();
		List<MtrNetwork.TrainInfo> trains = MtrNetwork.trains();
		for (MtrNetwork.TrainInfo train : trains) {
			JsonObject obj = new JsonObject();
			obj.addProperty("id", train.id());
			obj.addProperty("x", train.x());
			obj.addProperty("z", train.z());
			obj.addProperty("onRoute", train.onRoute());
			obj.addProperty("cars", train.cars());
			obj.addProperty("speedKmh", train.speedKmh());

			JsonArray names = new JsonArray();
			for (UUID uuid : train.riding()) {
				Player player = client.level == null ? null : client.level.getPlayerByUUID(uuid);
				if (player != null) {
					names.add(player.getName().getString());
				}
			}
			obj.addProperty("passengers", train.passengers());
			obj.add("players", names);

			obj.addProperty("routeId", train.routeId());
			obj.addProperty("routeName", train.routeName() == null ? "" : train.routeName());
			obj.addProperty("routeColor", train.routeColor());
			obj.addProperty("destination", train.destination() == null ? "" : train.destination());
			array.add(obj);
		}
		return array;
	}
}
