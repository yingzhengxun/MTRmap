package com.mtrmap.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import net.minecraft.server.MinecraftServer;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 地图数据请求的路由。
 *
 * <p>网页地图走 HTTP 端点、游戏内地图窗口走模组网络包，两条通道取的是同一份数据，
 * 所以取数逻辑收在这里，两边只是传输方式不同——这也保证了「网页与游戏内功能同步」。
 *
 * <p>所有方法都不抛异常：出错时返回一个 {@code {"error":true,"message":"..."}} 对象，
 * 让调用方统一当普通响应发回去。
 */
public final class MapRequestRouter {

	private static final Gson GSON = new GsonBuilder().create();

	private MapRequestRouter() {
	}

	/**
	 * 处理一次取数请求，返回响应体 JSON 文本。
	 *
	 * @param path   形如 {@code /api/data}，可带查询串
	 * @param body   POST 体（JSON 文本）；GET 请求传 null 或空串
	 * @param sender 发起方玩家；走网络包时是请求者本人（写操作一律记在他名下），
	 *               走 HTTP 时为 null（此时 uuid 以请求参数为准）
	 */
	public static String handle(MinecraftServer server, String path, String body, UUID sender) {
		try {
			String route = path == null ? "" : path;
			String query = "";
			int mark = route.indexOf('?');
			if (mark >= 0) {
				query = route.substring(mark + 1);
				route = route.substring(0, mark);
			}
			JsonObject payload = parseObject(body);
			switch (route) {
				case "/api/data":
					return GSON.toJson(MapDataCollector.collect(server, true));
				case "/api/players":
					return GSON.toJson(PlayerTracker.toJson());
				case "/api/trips":
					return trips(payload, query, sender);
				case "/api/trips/delete":
					return tripsDelete(payload, sender);
				case "/api/nav":
					return nav(payload, query, sender);
				default:
					return error("未知的地图数据请求：" + route);
			}
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.error("处理地图数据请求时发生异常：" + path, t);
			return error(t.getClass().getName() + ": " + t.getMessage());
		}
	}

	/** 行程记录：有 POST 体是新增，否则按 uuid 列出 */
	private static String trips(JsonObject payload, String query, UUID sender) {
		if (payload != null) {
			if (sender != null) {
				// 客户端回传的行程只能记在自己名下，忽略请求体里带的 uuid
				payload.addProperty("uuid", sender.toString());
			}
			JsonObject saved = TripStore.add(payload);
			JsonObject out = new JsonObject();
			out.addProperty("ok", saved != null);
			if (saved != null) {
				out.add("trip", saved);
			}
			return GSON.toJson(out);
		}
		return GSON.toJson(TripStore.list(uuidOf(query, payload, sender)));
	}

	/** 删除行程：POST {uuid, id} */
	private static String tripsDelete(JsonObject payload, UUID sender) {
		String uuid = sender != null ? sender.toString() : string(payload, "uuid");
		String id = string(payload, "id");
		JsonObject out = new JsonObject();
		out.addProperty("ok", TripStore.delete(uuid, id));
		return GSON.toJson(out);
	}

	/** 导航任务：有 POST 体是下发（网页），否则是客户端领取（领取即取走） */
	private static String nav(JsonObject payload, String query, UUID sender) {
		if (payload != null) {
			JsonObject task = payload.has("task") && payload.get("task").isJsonObject()
					? payload.getAsJsonObject("task") : null;
			String uuid = sender != null ? sender.toString() : string(payload, "uuid");
			NavTaskStore.put(uuid, task);
			JsonObject out = new JsonObject();
			out.addProperty("ok", true);
			return GSON.toJson(out);
		}
		JsonObject task = NavTaskStore.take(uuidOf(query, null, sender));
		// 没有任务时返回空对象，客户端据此判断「这次没领到」
		return task == null ? "{}" : GSON.toJson(task);
	}

	private static String uuidOf(String query, JsonObject payload, UUID sender) {
		if (sender != null) {
			return sender.toString();
		}
		String fromBody = string(payload, "uuid");
		return fromBody != null ? fromBody : queryParam(query, "uuid");
	}

	private static String string(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return null;
		}
		return obj.get(key).getAsString();
	}

	private static JsonObject parseObject(String json) {
		if (json == null || json.isEmpty()) {
			return null;
		}
		try {
			JsonElement root = MtrMapCommon.parseJson(json);
			return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** 取 URL 查询参数（已做 URL 解码） */
	private static String queryParam(String query, String name) {
		if (query == null) {
			return null;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			if (name.equals(pair.substring(0, eq))) {
				try {
					// URLDecoder.decode(String, Charset) 是 Java 10 才加的，1.16.5 只能用名字
					return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
				} catch (Exception e) {
					return pair.substring(eq + 1);
				}
			}
		}
		return null;
	}

	private static String error(String message) {
		JsonObject err = new JsonObject();
		err.addProperty("error", true);
		err.addProperty("message", message);
		return GSON.toJson(err);
	}
}
