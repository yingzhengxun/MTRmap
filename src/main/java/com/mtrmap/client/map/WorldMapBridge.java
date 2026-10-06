package com.mtrmap.client.map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;

/**
 * 自研世界地图（底图）接入：向本模组自己的 HTTP 服务要瓦片参数与瓦片，
 * 不依赖 squaremap 等外部地图模组。
 *
 * <p>参数（瓦片边长、缩放范围）在一次会话里不会变，所以只在后台线程取一次；
 * 渲染线程只读缓存结果，取不到时 {@link Settings#ok()} 为 false，由界面给出提示。
 */
public final class WorldMapBridge {

	/** 参数取失败后的重试间隔 */
	private static final long FAILED_RETRY_MS = 5_000L;

	private static volatile Settings cached;
	private static volatile long cachedAt;
	private static volatile boolean loading;

	private WorldMapBridge() {
	}

	/** 瓦片参数 */
	public static final class Settings {
		public int tileSize = 512;
		public int maxZoom = 4;
		public int minZoom = 0;
		public String world = "";
		/** 参数已经取到 */
		public boolean ready;
		/** 未就绪时的可读原因，供界面显示 */
		public String detail;

		public boolean ok() {
			return ready;
		}
	}

	/** 渲染线程调用：立刻返回缓存结果，必要时在后台线程重新取，绝不阻塞。 */
	public static Settings settings() {
		long now = System.currentTimeMillis();
		Settings current = cached;
		if (current != null && (current.ready || now - cachedAt < FAILED_RETRY_MS)) {
			return current;
		}
		startLoad();
		return current != null ? current : loading("正在读取世界地图…");
	}

	/** 后台线程调用：确保拿到参数（导出图片用），最多等 timeoutMs */
	public static Settings await(long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (true) {
			Settings current = cached;
			if (current != null && current.ready) {
				return current;
			}
			if (!loading) {
				return load();
			}
			if (System.currentTimeMillis() >= deadline) {
				return current != null ? current : loading("正在读取世界地图…");
			}
			try {
				Thread.sleep(50L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return current != null ? current : loading("正在读取世界地图…");
			}
		}
	}

	/** 断开连接 / 换存档时清空 */
	public static void invalidate() {
		cached = null;
		cachedAt = 0L;
	}

	private static Settings loading(String detail) {
		Settings settings = new Settings();
		settings.detail = detail;
		return settings;
	}

	private static void startLoad() {
		if (loading) {
			return;
		}
		loading = true;
		Thread thread = new Thread(() -> {
			try {
				load();
			} finally {
				loading = false;
			}
		}, "MTRMap-WorldMap");
		thread.setDaemon(true);
		thread.start();
	}

	private static Settings load() {
		Settings settings = new Settings();
		JsonElement root = parse(MapDataClient.getString("/api/worldmap/settings"));
		if (root == null || !root.isJsonObject()) {
			settings.detail = "读不到世界地图参数（地图服务可能还没起来）";
			cached = settings;
			cachedAt = System.currentTimeMillis();
			return settings;
		}
		JsonObject json = root.getAsJsonObject();
		settings.tileSize = intValue(json, "tileSize", 512);
		settings.maxZoom = intValue(json, "maxZoom", 4);
		settings.minZoom = intValue(json, "minZoom", 0);
		settings.world = string(json, "world", "");
		if (settings.tileSize <= 0 || settings.maxZoom < settings.minZoom) {
			settings.detail = "世界地图参数异常";
			cached = settings;
			cachedAt = System.currentTimeMillis();
			return settings;
		}
		settings.ready = true;
		settings.detail = null;
		cached = settings;
		cachedAt = System.currentTimeMillis();
		return settings;
	}

	/** 瓦片路径（交给 MapDataClient 拼上本机服务地址） */
	public static String tilePath(int zoom, int tileX, int tileY) {
		return "/api/worldmap/" + zoom + "/" + tileX + "_" + tileY + ".png";
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

	private static String string(JsonObject obj, String key, String fallback) {
		JsonElement element = obj.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static int intValue(JsonObject obj, String key, int fallback) {
		JsonElement element = obj.get(key);
		try {
			return element == null || element.isJsonNull() ? fallback : element.getAsInt();
		} catch (Exception e) {
			return fallback;
		}
	}
}
