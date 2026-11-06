package com.mtrmap.client.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.client.MapChannel;
import com.mtrmap.server.MtrNetwork;
import net.minecraft.client.Minecraft;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 游戏内地图窗口的数据客户端。
 *
 * <p>数据直接从服务端取：本类把要问的端点（/api/data、/api/players、/api/trips …）
 * 交给 {@link MapChannel}，由它走模组网络包发给服务端、再把分块回包拼回 JSON。
 * 这样 F6 窗口完全不依赖地图 HTTP 服务（1145 端口），专用服务端上也不必再操心端口。
 *
 * <p>只在窗口打开时轮询（每 2 秒一次，与网页一致），关掉窗口就停，避免空转。
 */
public final class MapDataClient {

	/** 轮询间隔：与网页的地图刷新节奏一致 */
	private static final long POLL_INTERVAL_MS = 2000L;

	private static final MapModel EMPTY = new MapModel();
	/** 当前快照：轮询线程整体替换，渲染线程只读，靠 volatile 保证可见性 */
	private static volatile MapModel current = EMPTY;
	private static volatile boolean active;
	private static volatile boolean threadStarted;
	private static volatile boolean refreshNow;
	/** 最近一次取数失败的原因（null 表示当前是通的）；窗口会把它显示出来，便于直接看出问题 */
	private static volatile String lastError;
	/** 「已连接」日志只打一次，避免每 2 秒刷一行 */
	private static volatile boolean connectionLogged;

	private MapDataClient() {
	}

	public static MapModel model() {
		return current;
	}

	/** 最近一次取数失败的原因；null 表示正常 */
	public static String lastError() {
		return lastError;
	}

	/** 窗口打开：开始轮询并立刻取一次 */
	public static void open() {
		active = true;
		refreshNow = true;
		if (!threadStarted) {
			threadStarted = true;
			Thread thread = new Thread(MapDataClient::loop, "MTRMap-DataPoll");
			thread.setDaemon(true);
			thread.start();
		}
	}

	/** 窗口关闭：停止轮询（数据保留，下次打开先看到旧图再刷新） */
	public static void close() {
		active = false;
	}

	/** 断开连接：清空模型并放弃在途请求 */
	public static void onDisconnect() {
		active = false;
		current = EMPTY;
		lastError = null;
		connectionLogged = false;
		MapChannel.reset();
		// 上一台服务器的客户端线网快照不能留到下一台服务器
		MtrNetwork.clearClient();
	}

	/** 立即刷新一次（切换站点开关等场景不用等下一个周期） */
	public static void requestRefresh() {
		refreshNow = true;
	}

	private static void loop() {
		while (true) {
			try {
				if (active || refreshNow) {
					refreshNow = false;
					MapModel model = current;
					String before = lastError;
					if (MtrMapCommon.hasServerMod()) {
						String body = MapChannel.request("/api/data", null);
						JsonObject data = asObject(body);
						if (data != null) {
							// 线网数据里没有玩家，玩家由 /api/players 一并补上
							MapModel fresh = MapModel.fromData(data);
							JsonArray players = asArray(MapChannel.request("/api/players", null));
							current = players == null ? fresh : fresh.withPlayers(players);
							lastError = null;
						} else {
							JsonArray players = asArray(MapChannel.request("/api/players", null));
							if (players != null) {
								current = model.withPlayers(players);
							}
							lastError = MapChannel.lastError() != null ? MapChannel.lastError() : "尚未收到服务端数据";
						}
					} else {
						// 服务端没装本模组：判定用户只需要游戏内地图，改用客户端 MTR 缓存
						MapModel fresh = clientSnapshot();
						if (fresh != null) {
							current = fresh;
							lastError = null;
						} else if (lastError == null) {
							lastError = "服务端未安装本模组，正在读取客户端线路数据";
						}
					}
					logStateChange(before);
				}
			} catch (Throwable t) {
				lastError = describe(t);
			}
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/**
	 * 取数状态发生变化时打一行日志：一直失败会刷屏，只在「通 → 不通」或「不通 → 通」时记录。
	 */
	private static void logStateChange(String before) {
		String now = lastError;
		if (java.util.Objects.equals(before, now) && (now != null || connectionLogged)) {
			return;
		}
		if (now == null) {
			connectionLogged = true;
			MtrMapCommon.LOGGER.info("已从服务端取得地图数据，车站 {} 个", current.stations.size());
		} else {
			MtrMapCommon.LOGGER.warn("从服务端获取地图数据失败：{}", now);
		}
	}

	/**
	 * 服务端没装本模组时的取数：在客户端线程刷新 MTR 缓存快照，再在轮询线程组装模型。
	 * 最多等 500ms，客户端线程卡住时这一轮跳过，不拖住轮询。
	 */
	private static MapModel clientSnapshot() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return null;
		}
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		client.execute(() -> {
			try {
				MapClientData.refresh();
			} finally {
				latch.countDown();
			}
		});
		try {
			if (!latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
				return null;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		JsonObject data = MapClientData.collect();
		MapModel model = MapModel.fromData(data);
		JsonArray players = asArray(data, "players");
		if (players != null) {
			model = model.withPlayers(players);
		}
		return model.stations.isEmpty() && model.routes.isEmpty() ? null : model;
	}

	private static JsonArray asArray(JsonObject obj, String key) {
		JsonElement element = obj.get(key);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		return message == null || message.isEmpty() ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
	}

	// ===== 请求 =====

	private static JsonObject asObject(String body) {
		JsonElement root = parse(body);
		return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
	}

	private static JsonArray asArray(String body) {
		JsonElement root = parse(body);
		return root != null && root.isJsonArray() ? root.getAsJsonArray() : null;
	}

	private static JsonElement parse(String body) {
		if (body == null) {
			return null;
		}
		try {
			return MtrMapCommon.parseJson(body);
		} catch (Exception e) {
			return null;
		}
	}

	/** 后台异步请求（下发导航 / 行程回传），失败静默；服务端没装本模组时直接跳过 */
	public static void postAsync(String path, JsonObject body) {
		if (!MtrMapCommon.hasServerMod()) {
			return;
		}
		String json = body.toString();
		Thread thread = new Thread(() -> MapChannel.request(path, json), "MTRMap-MapPost");
		thread.setDaemon(true);
		thread.start();
	}

	/** 同步请求，返回是否成功；服务端没装本模组时直接返回 false */
	public static boolean postSync(String path, JsonObject body) {
		return MtrMapCommon.hasServerMod() && MapChannel.request(path, body.toString()) != null;
	}

	/** 带查询参数的 GET（如 /api/trips?uuid=） */
	public static JsonElement getWithParam(String path, String name, String value) {
		if (!MtrMapCommon.hasServerMod()) {
			return null;
		}
		try {
			String encoded = URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
			return parse(MapChannel.request(path + "?" + name + "=" + encoded, null));
		} catch (Exception e) {
			return null;
		}
	}
}
