package com.mtrmap.client.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * squaremap 接入层：解析它的 Web 端口与世界参数，供游戏内地图窗口取瓦片底图。
 *
 * <p>端口来源优先级：
 * <ol>
 *   <li>squaremap 自己的 {@code Config} 静态字段（编译期依赖，运行期由加载器保证它在）；</li>
 *   <li>回退解析 {@code config/squaremap/config.yml}；</li>
 *   <li>都拿不到时用 squaremap 默认的 8080。</li>
 * </ol>
 * 世界列表与缩放级别直接走 squaremap 的 HTTP 静态资源
 * （{@code tiles/settings.json} 与 {@code tiles/<world>/settings.json}），
 * 这样不依赖任何内部 API，跨版本稳定。
 */
public final class SquaremapBridge {

	/** squaremap 默认 Web 端口 */
	private static final int DEFAULT_PORT = 8080;
	private static final int TIMEOUT_MS = 1500;

	/** 缓存下来的结果（世界与缩放参数在游戏运行期不会变） */
	private static Result cached;
	/** 失败结果的重试冷却，避免 squaremap 还没起来时每帧都重试 */
	private static long cachedAt;
	private static final long FAILED_RETRY_MS = 10_000L;

	private SquaremapBridge() {
	}

	/** 一次解析出来的 squaremap 接入信息 */
	public static final class Result {
		public String baseUrl;
		public String world;
		/** 该世界的最大原生缩放级别（瓦片最细一级） */
		public int maxZoom = 3;
		public int minZoom;
		public String error;

		public boolean ok() {
			return error == null && baseUrl != null && world != null;
		}
	}

	public static synchronized Result resolve() {
		if (cached != null) {
			// 解析成功就一直用；失败则 10 秒后重试（squaremap 可能还没启动完）
			if (cached.ok() || System.currentTimeMillis() - cachedAt < FAILED_RETRY_MS) {
				return cached;
			}
		}
		Result result = new Result();
		int port = resolvePort();
		result.baseUrl = "http://127.0.0.1:" + port;
		String[] worldAndZoom = resolveWorld(result.baseUrl);
		if (worldAndZoom == null) {
			result.error = "无法读取 squaremap 世界列表（tiles/settings.json）";
		} else {
			result.world = worldAndZoom[0];
			result.maxZoom = Integer.parseInt(worldAndZoom[1]);
		}
		cached = result;
		cachedAt = System.currentTimeMillis();
		if (result.ok()) {
			MtrMapCommon.LOGGER.info("游戏内地图：squaremap 底图 {} 世界={} 最大缩放={}",
					result.baseUrl, result.world, result.maxZoom);
		} else {
			MtrMapCommon.LOGGER.warn("游戏内地图：squaremap 底图不可用（{}）", result.error);
		}
		return result;
	}

	/** 清除缓存（配置改变或断线重连时调用） */
	public static synchronized void invalidate() {
		cached = null;
		cachedAt = 0L;
	}

	// ===== 端口 =====

	private static int resolvePort() {
		// 1) 直接读 squaremap 的 Config（与它自己的运行期配置一致）
		try {
			if (!xyz.jpenilla.squaremap.common.config.Config.HTTPD_ENABLED) {
				MtrMapCommon.LOGGER.warn("游戏内地图：squaremap 的内置 Web 服务器未开启（httpd.enabled=false）");
			} else if (xyz.jpenilla.squaremap.common.config.Config.HTTPD_PORT > 0) {
				return xyz.jpenilla.squaremap.common.config.Config.HTTPD_PORT;
			}
		} catch (Throwable ignored) {
			// 未初始化 / 类不可用：继续走配置文件
		}
		// 2) 回退：解析 config/squaremap/config.yml
		Integer fromFile = readPortFromConfigFile();
		return fromFile != null ? fromFile : DEFAULT_PORT;
	}

	private static Integer readPortFromConfigFile() {
		try {
			Path path = Platform.getConfigDir().resolve("config/squaremap/config.yml");
			if (!Files.exists(path)) {
				return null;
			}
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			int httpdIndent = -1;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				int indent = indentOf(line);
				if (httpdIndent < 0) {
					if (trimmed.startsWith("httpd:")) {
						httpdIndent = indent;
					}
					continue;
				}
				// 退回与 httpd 同级或更外层，说明 httpd 段结束
				if (indent <= httpdIndent) {
					break;
				}
				if (trimmed.startsWith("port:")) {
					String value = trimmed.substring("port:".length()).trim();
					return Integer.parseInt(value);
				}
			}
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.debug("解析 squaremap config.yml 失败", t);
		}
		return null;
	}

	private static int indentOf(String line) {
		int count = 0;
		while (count < line.length() && line.charAt(count) == ' ') {
			count++;
		}
		return count;
	}

	// ===== 世界列表与缩放 =====

	/** 返回 [worldName, maxZoom]，失败返回 null */
	private static String[] resolveWorld(String baseUrl) {
		JsonObject settings = getJson(baseUrl + "/tiles/settings.json");
		if (settings == null) {
			return null;
		}
		JsonElement worldsElement = settings.get("worlds");
		if (worldsElement == null || !worldsElement.isJsonArray()) {
			// 兼容直接返回数组的情况
			JsonArray asArray = settings.getAsJsonArray("worlds");
			if (asArray == null) {
				return null;
			}
			worldsElement = asArray;
		}
		// 按 order 升序挑第一个世界（squaremap 默认把主世界排在前面）
		String bestName = null;
		int bestOrder = Integer.MAX_VALUE;
		for (JsonElement element : worldsElement.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject world = element.getAsJsonObject();
			String name = string(world, "name", null);
			if (name == null || name.isEmpty()) {
				continue;
			}
			int order = intValue(world, "order", Integer.MAX_VALUE);
			if (order < bestOrder) {
				bestOrder = order;
				bestName = name;
			}
		}
		if (bestName == null) {
			return null;
		}
		int maxZoom = 3;
		JsonObject worldSettings = getJson(baseUrl + "/tiles/" + bestName + "/settings.json");
		if (worldSettings != null && worldSettings.has("zoom") && worldSettings.get("zoom").isJsonObject()) {
			JsonObject zoom = worldSettings.getAsJsonObject("zoom");
			int max = intValue(zoom, "max", 3);
			if (max > 0) {
				maxZoom = max;
			}
		}
		return new String[]{bestName, String.valueOf(maxZoom)};
	}

	/** 瓦片 URL：{@code tiles/<world>/{z}/{x}_{y}.png} */
	public static String tileUrl(Result result, int zoom, int tileX, int tileY) {
		return result.baseUrl + "/tiles/" + result.world + "/" + zoom + "/" + tileX + "_" + tileY + ".png";
	}

	// ===== HTTP =====

	static JsonObject getJson(String url) {
		byte[] bytes = getBytes(url);
		if (bytes == null) {
			return null;
		}
		try {
			JsonElement root = MtrMapCommon.parseJson(new String(bytes, StandardCharsets.UTF_8));
			return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	static byte[] getBytes(String url) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setConnectTimeout(TIMEOUT_MS);
			conn.setReadTimeout(TIMEOUT_MS);
			conn.setRequestMethod("GET");
			if (conn.getResponseCode() != 200) {
				return null;
			}
			try (InputStream is = conn.getInputStream()) {
				return MtrMapCommon.readAll(is);
			}
		} catch (Throwable t) {
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
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
