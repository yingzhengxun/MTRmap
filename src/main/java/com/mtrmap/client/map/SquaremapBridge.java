package com.mtrmap.client.map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * squaremap 接入层：解析它的 Web 端口与世界参数，供游戏内地图窗口取瓦片底图。
 *
 * <p>端口不是猜的，而是按候选地址逐个真连一次 {@code tiles/settings.json}，第一个连通的才算数。
 * 候选顺序：
 * <ol>
 *   <li>squaremap 自己的 {@code Config.HTTPD_PORT}（编译期依赖，正常情况就是它）；</li>
 *   <li>回退解析 {@code config/squaremap/config.yml} 的 {@code settings.internal-webserver.port}
 *       （旧版 squaremap 是顶层 {@code httpd.port}）；</li>
 *   <li>squaremap 默认的 8080；</li>
 *   <li>{@code Config.WEB_ADDRESS}（squaremap 对外公布的地址，端口改过或被反代时有用）。</li>
 * </ol>
 *
 * <p>解析全程在后台线程做：{@link #resolve()} 只返回缓存结果（渲染线程永不阻塞），
 * 失败后 10 秒重试一次，并把失败原因记进 {@link Result#detail} 供界面与日志显示。
 */
public final class SquaremapBridge {

	/** squaremap 默认 Web 端口 */
	private static final int DEFAULT_PORT = 8080;
	private static final int TIMEOUT_MS = 1500;
	/** 失败结果的重试冷却，避免 squaremap 还没起来时反复重试 */
	private static final long FAILED_RETRY_MS = 10_000L;

	private static volatile Result cached;
	private static volatile long cachedAt;
	private static volatile boolean resolving;
	/** squaremap 的 httpd 明确处于关闭状态 */
	private static volatile boolean httpdDisabled;
	/** 上一次探测 settings.json 的失败原因（只在探测路径里写） */
	private static volatile String probeError;

	private SquaremapBridge() {
	}

	/** 一次解析出来的 squaremap 接入信息 */
	public static final class Result {
		public String baseUrl;
		public String world;
		/** 该世界的最大原生缩放级别（瓦片最细一级） */
		public int maxZoom = 3;
		/** 失败时的可读原因（给界面和日志用），成功时为 null */
		public String detail;

		public boolean ok() {
			return baseUrl != null && world != null;
		}
	}

	/**
	 * 渲染线程调用：立刻返回当前缓存的结果，必要时在后台线程重新解析，绝不阻塞。
	 *
	 * <p>首次调用返回的是「正在解析」占位结果，此时 {@link Result#ok()} 为 false。
	 */
	public static Result resolve() {
		long now = System.currentTimeMillis();
		Result current = cached;
		if (current != null && (current.ok() || now - cachedAt < FAILED_RETRY_MS)) {
			return current;
		}
		startResolve();
		return current != null ? current : running();
	}

	/**
	 * 后台线程调用：确保拿到一个确定的结果（导出图片用，等得起）。
	 *
	 * @param timeoutMs 最多等多久（毫秒）
	 */
	public static Result await(long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (true) {
			Result current = cached;
			if (current != null && current.ok()) {
				return current;
			}
			if (!resolving) {
				// 没有正在进行的解析：本方法约定在后台线程调用，直接同步解析一次
				return publish(doResolve());
			}
			if (System.currentTimeMillis() >= deadline) {
				return current != null ? current : running();
			}
			try {
				Thread.sleep(50L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return current != null ? current : running();
			}
		}
	}

	/** 清除缓存（配置改变或断线重连时调用） */
	public static synchronized void invalidate() {
		cached = null;
		cachedAt = 0L;
	}

	private static Result running() {
		Result result = new Result();
		result.detail = "正在读取 squaremap 底图……";
		return result;
	}

	private static void startResolve() {
		if (resolving) {
			return;
		}
		resolving = true;
		Thread thread = new Thread(() -> {
			try {
				publish(doResolve());
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.warn("游戏内地图：解析 squaremap 底图失败", t);
			} finally {
				resolving = false;
			}
		}, "MTRMap-Squaremap");
		thread.setDaemon(true);
		thread.start();
	}

	private static Result publish(Result result) {
		cached = result;
		cachedAt = System.currentTimeMillis();
		if (result.ok()) {
			MtrMapCommon.LOGGER.info("游戏内地图：squaremap 底图 {} 世界={} 最大缩放={}",
					result.baseUrl, result.world, result.maxZoom);
		} else {
			MtrMapCommon.LOGGER.warn("游戏内地图：squaremap 底图不可用（{}）", result.detail);
		}
		return result;
	}

	// ===== 解析 =====

	private static Result doResolve() {
		Result result = new Result();
		httpdDisabled = false;
		List<String> tried = new ArrayList<>();
		for (String baseUrl : candidateBaseUrls()) {
			probeError = null;
			String[] worldAndZoom = resolveWorld(baseUrl);
			if (worldAndZoom != null) {
				result.baseUrl = baseUrl;
				result.world = worldAndZoom[0];
				result.maxZoom = Integer.parseInt(worldAndZoom[1]);
				result.detail = null;
				return result;
			}
			tried.add(baseUrl + "（" + (probeError == null ? "无响应" : probeError) + "）");
		}
		if (httpdDisabled) {
			result.detail = "squaremap 的内置 Web 服务器未开启"
					+ "（settings.internal-webserver.enabled=false），请在 squaremap 配置里打开后重进世界";
		} else {
			result.detail = "连不上 squaremap 底图服务，已尝试 " + String.join("、", tried)
					+ "；请确认 squaremap 已安装、内置 Web 服务器已开启";
		}
		return result;
	}

	/** 候选地址（去重，本机地址优先） */
	private static List<String> candidateBaseUrls() {
		Set<String> candidates = new LinkedHashSet<>();
		Integer fromConfig = readConfigStaticPort();
		if (fromConfig != null) {
			candidates.add("http://127.0.0.1:" + fromConfig);
		}
		Integer fromFile = readPortFromConfigFile();
		if (fromFile != null) {
			candidates.add("http://127.0.0.1:" + fromFile);
		}
		candidates.add("http://127.0.0.1:" + DEFAULT_PORT);
		String webAddress = normalizeWebAddress(readWebAddress());
		if (webAddress != null) {
			candidates.add(webAddress);
		}
		return new ArrayList<>(candidates);
	}

	/** squaremap 的 Config 静态字段（未初始化 / 类不可用时返回 null） */
	private static Integer readConfigStaticPort() {
		try {
			if (!xyz.jpenilla.squaremap.common.config.Config.HTTPD_ENABLED) {
				httpdDisabled = true;
				MtrMapCommon.LOGGER.warn("游戏内地图：squaremap 的内置 Web 服务器未开启（internal-webserver.enabled=false）");
				return null;
			}
			if (xyz.jpenilla.squaremap.common.config.Config.HTTPD_PORT > 0) {
				return xyz.jpenilla.squaremap.common.config.Config.HTTPD_PORT;
			}
		} catch (Throwable ignored) {
			// 未初始化 / 类不可用：继续走配置文件
		}
		return null;
	}

	private static String readWebAddress() {
		try {
			return xyz.jpenilla.squaremap.common.config.Config.WEB_ADDRESS;
		} catch (Throwable ignored) {
			return null;
		}
	}

	/** 把 squaremap 公布的地址整成可用的 base url（本机别名换回 127.0.0.1） */
	private static String normalizeWebAddress(String address) {
		if (address == null || address.trim().isEmpty()) {
			return null;
		}
		String value = address.trim();
		if (!value.startsWith("http://") && !value.startsWith("https://")) {
			value = "http://" + value;
		}
		try {
			URL url = new URL(value);
			String host = url.getHost();
			if (host == null || host.isEmpty() || "0.0.0.0".equals(host) || "::".equals(host)
					|| "[::]".equals(host) || "localhost".equalsIgnoreCase(host)) {
				host = "127.0.0.1";
			}
			int port = url.getPort() > 0 ? url.getPort() : url.getDefaultPort();
			return url.getProtocol() + "://" + host + (port > 0 ? ":" + port : "");
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * 解析 {@code config/squaremap/config.yml} 里的端口。
	 *
	 * <p>squaremap 1.2 的配置是
	 * {@code settings.internal-webserver.port}；更早的版本是顶层 {@code httpd.port}。
	 */
	private static Integer readPortFromConfigFile() {
		try {
			Path path = Platform.getConfigDir().resolve("config/squaremap/config.yml");
			if (!Files.exists(path)) {
				return null;
			}
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			int sectionIndent = -1;
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				int indent = indentOf(line);
				if (sectionIndent < 0) {
					if (trimmed.startsWith("internal-webserver:") || trimmed.startsWith("httpd:")) {
						sectionIndent = indent;
					}
					continue;
				}
				// 缩进回到同级或更外层，说明 port 段已经结束
				if (indent <= sectionIndent) {
					return null;
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

	/** 返回 [worldName, maxZoom]，失败返回 null 并把原因写进 {@link #probeError} */
	private static String[] resolveWorld(String baseUrl) {
		JsonObject settings = requestJson(baseUrl + "/tiles/settings.json");
		if (settings == null) {
			return null;
		}
		JsonElement worldsElement = settings.get("worlds");
		if (worldsElement == null || !worldsElement.isJsonArray()) {
			probeError = "settings.json 里没有 worlds";
			return null;
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
			probeError = "settings.json 里没有可用世界";
			return null;
		}
		int maxZoom = 3;
		JsonObject worldSettings = requestJson(baseUrl + "/tiles/" + bestName + "/settings.json");
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

	/** 探测用：失败原因写进 {@link #probeError} */
	private static JsonObject requestJson(String url) {
		Fetch fetch = fetch(url);
		probeError = fetch.error;
		if (fetch.bytes == null) {
			return null;
		}
		try {
			JsonElement root = MtrMapCommon.parseJson(new String(fetch.bytes, StandardCharsets.UTF_8));
			if (root == null || !root.isJsonObject()) {
				probeError = "返回内容不是 JSON";
				return null;
			}
			return root.getAsJsonObject();
		} catch (Exception e) {
			probeError = "返回内容不是 JSON";
			return null;
		}
	}

	static byte[] getBytes(String url) {
		return fetch(url).bytes;
	}

	private static final class Fetch {
		final byte[] bytes;
		final String error;

		Fetch(byte[] bytes, String error) {
			this.bytes = bytes;
			this.error = error;
		}
	}

	private static Fetch fetch(String url) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setConnectTimeout(TIMEOUT_MS);
			conn.setReadTimeout(TIMEOUT_MS);
			conn.setRequestMethod("GET");
			int code = conn.getResponseCode();
			if (code != 200) {
				return new Fetch(null, "HTTP " + code);
			}
			try (InputStream is = conn.getInputStream()) {
				return new Fetch(MtrMapCommon.readAll(is), null);
			}
		} catch (SocketTimeoutException e) {
			return new Fetch(null, "连接超时");
		} catch (ConnectException e) {
			return new Fetch(null, "连接被拒绝");
		} catch (Throwable t) {
			String message = t.getMessage();
			return new Fetch(null, t.getClass().getSimpleName() + (message == null ? "" : "：" + message));
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
