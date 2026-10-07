package com.mtrmap.client.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 游戏内地图窗口的数据客户端：走本机 MTR Map HTTP 服务（与网页同一套端点），
 * 把 /api/data、/api/players 拉回来喂给 {@link MapModel}，并负责下发导航任务。
 *
 * <p>只在窗口打开时轮询（每 2 秒一次，与网页一致），关掉窗口就停，避免空转。
 */
public final class MapDataClient {

	/** 轮询间隔：与网页的地图刷新节奏一致 */
	private static final long POLL_INTERVAL_MS = 2000L;
	/** 连接超时：连不上就尽快失败，别把轮询卡住 */
	private static final int CONNECT_TIMEOUT_MS = 1500;
	/** 读取超时：线网大时服务端拼 JSON 要花点时间，给宽一点（网页也是等浏览器默认的几十秒） */
	private static final int READ_TIMEOUT_MS = 5000;

	private static final MapModel EMPTY = new MapModel();
	/** 当前快照：轮询线程整体替换，渲染线程只读，靠 volatile 保证可见性 */
	private static volatile MapModel current = EMPTY;
	private static volatile boolean active;
	private static volatile boolean threadStarted;
	private static volatile boolean refreshNow;
	/** 最近一次连接失败的原因（null 表示当前是通的）；窗口会把它显示出来，便于直接看出问题 */
	private static volatile String lastError;
	/** 「已连接」日志只打一次，避免每 2 秒刷一行 */
	private static volatile boolean connectionLogged;

	private MapDataClient() {
	}

	public static MapModel model() {
		return current;
	}

	/** 地图服务地址（例如 http://例子.com:1145），排查连不上问题时用 */
	public static String baseUrl() {
		return base();
	}

	/** 最近一次连接失败的原因；null 表示正常 */
	public static String lastError() {
		return lastError;
	}

	/** 窗口打开：开始轮询并立刻拉一次 */
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

	/** 断开连接：清空模型 */
	public static void onDisconnect() {
		active = false;
		current = EMPTY;
		lastError = null;
		connectionLogged = false;
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
					JsonObject data = getJson("/api/data");
					if (data != null) {
						// 线网数据里没有玩家，玩家由 /api/players 一并补上
						MapModel fresh = MapModel.fromData(data);
						JsonArray players = getArray("/api/players");
						current = players == null ? fresh : fresh.withPlayers(players);
					} else {
						JsonArray players = getArray("/api/players");
						if (players != null) {
							current = model.withPlayers(players);
						}
					}
					logStateChange(before);
				}
			} catch (Throwable t) {
				// 服务端未启动 / 端口未监听时静默重试
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
	 * 连接状态发生变化时打一行日志：一直连不上会刷屏，只在「通 → 不通」或「不通 → 通」时记录。
	 * 专用服务端上最常见的失败就是客户端用错了地址/端口，这一行能直接看出来。
	 */
	private static void logStateChange(String before) {
		String now = lastError;
		if (java.util.Objects.equals(before, now) && (now != null || connectionLogged)) {
			return;
		}
		if (now == null) {
			connectionLogged = true;
			MtrMapCommon.LOGGER.info("已连接地图服务 {}，车站 {} 个", base(), current.stations.size());
		} else {
			MtrMapCommon.LOGGER.warn("连接地图服务 {} 失败：{}", base(), now);
		}
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		return message == null || message.isEmpty() ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
	}

	// ===== HTTP =====

	/** 地图服务地址：连哪台服务器就访问哪台（专用服务端上不能写死 127.0.0.1） */
	private static String base() {
		return com.mtrmap.client.MapEndpoint.base();
	}

	private static JsonObject getJson(String path) {
		JsonElement root = parse(getString(path));
		return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
	}

	private static JsonArray getArray(String path) {
		JsonElement root = parse(getString(path));
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

	/** 同步 GET，返回响应体；失败返回 null */
	public static String getString(String path) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(base() + path).openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setRequestMethod("GET");
			int code = conn.getResponseCode();
			if (code != 200) {
				lastError = "HTTP " + code;
				return null;
			}
			try (InputStream is = conn.getInputStream()) {
				String body = new String(MtrMapCommon.readAll(is), StandardCharsets.UTF_8);
				lastError = null;
				return body;
			}
		} catch (Throwable t) {
			lastError = describe(t);
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	/** 异步 POST JSON（下发导航 / 行程回传），失败静默 */
	public static void postAsync(String path, JsonObject body) {
		String json = body.toString();
		Thread thread = new Thread(() -> post(path, json), "MTRMap-MapPost");
		thread.setDaemon(true);
		thread.start();
	}

	/** 同步 POST JSON，返回是否成功 */
	public static boolean postSync(String path, JsonObject body) {
		return post(path, body.toString());
	}

	/** 带查询参数的 GET（如 /api/trips?uuid=） */
	public static JsonElement getWithParam(String path, String name, String value) {
		try {
			String encoded = URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
			return parse(getString(path + "?" + name + "=" + encoded));
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean post(String path, String json) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(base() + path).openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "application/json");
			try (OutputStream os = conn.getOutputStream()) {
				os.write(json.getBytes(StandardCharsets.UTF_8));
			}
			return conn.getResponseCode() == 200;
		} catch (Throwable t) {
			// 回传失败不影响游戏
			return false;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}
}
