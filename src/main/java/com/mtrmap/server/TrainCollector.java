package com.mtrmap.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.server.MtrNetwork.TrainInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 把 MTR 的列车数据写成 JSON。
 *
 * 列车本身的取法在 MTR 3.x / 4.x 上完全不同（3.x 是 {@code Siding.trains} + 自己按轨长算位置，
 * 4.x 是 {@code Siding.iterateVehicles} + 车头位置直接可读），这些差异全在
 * {@link MtrNetwork#trains()} 里消化，这里只负责组装 JSON 和把乘客 UUID 换成玩家名。
 */
public class TrainCollector {

	/** 采集所有列车并写入 JSON 数组 */
	public static void collect(MinecraftServer server, JsonArray trainsArray) {
		if (server == null) {
			return;
		}
		List<TrainInfo> trains = MtrNetwork.trains();
		for (TrainInfo train : trains) {
			JsonObject obj = new JsonObject();
			obj.addProperty("id", train.id());
			obj.addProperty("x", train.x());
			obj.addProperty("z", train.z());
			obj.addProperty("onRoute", train.onRoute());
			obj.addProperty("cars", train.cars());
			obj.addProperty("speedKmh", train.speedKmh());

			// 只列出当前在线的乘客；离线玩家（UUID 解析不到名字）不显示
			JsonArray players = new JsonArray();
			for (UUID uuid : train.riding()) {
				ServerPlayer player = server.getPlayerList().getPlayer(uuid);
				if (player != null) {
					players.add(player.getName().getString());
				}
			}
			obj.addProperty("passengers", train.passengers());
			obj.add("players", players);

			obj.addProperty("routeId", train.routeId());
			obj.addProperty("routeName", train.routeName() == null ? "" : train.routeName());
			obj.addProperty("routeColor", train.routeColor());
			obj.addProperty("destination", train.destination() == null ? "" : train.destination());
			trainsArray.add(obj);
		}

		// 限频输出列车数量，便于排查显示问题
		logTrainCount(trainsArray.size());
	}

	/** 限频输出列车数量，便于排查显示问题 */
	private static long lastLogTime = 0;

	private static void logTrainCount(int count) {
		long now = System.currentTimeMillis();
		if (now - lastLogTime > 60_000) {
			lastLogTime = now;
			MtrMapCommon.LOGGER.info("MTR Map 已采集 {} 辆列车", count);
		}
	}
}
