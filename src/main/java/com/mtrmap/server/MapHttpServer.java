package com.mtrmap.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import net.minecraft.server.MinecraftServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 基于 com.sun.net.httpserver 的轻量 HTTP 服务器。
 * 监听配置端口（默认 1145），提供地图网页与 JSON API。
 *
 * 端点：
 *   GET  /              -> index.html
 *   GET  /style.css     -> style.css
 *   GET  /map.js        -> map.js
 *   GET  /api/data      -> 车站/线路/车厂 JSON
 *   GET  /api/players   -> 玩家位置 JSON
 *   GET  /api/whoami    -> 按请求来源 IP 识别是哪个在线玩家
 *   POST /api/nav       -> 网页向某玩家下发导航任务
 *   GET  /api/nav       -> 游戏客户端轮询领取导航任务
 *   GET  /api/trips     -> 某玩家的行程记录
 *   POST /api/trips     -> 新增一条行程记录
 *   POST /api/trips/delete -> 删除一条行程记录
 *   GET  /avatar/{uuid} -> 玩家头像 PNG
 */
public class MapHttpServer {

	private static final Gson GSON = new GsonBuilder().create();
	private static HttpServer server;
	private static MinecraftServer minecraftServer;
	private static final Map<String, String> MIME_TYPES = new HashMap<>();

	static {
		MIME_TYPES.put("html", "text/html; charset=UTF-8");
		MIME_TYPES.put("css", "text/css; charset=UTF-8");
		MIME_TYPES.put("js", "application/javascript; charset=UTF-8");
		MIME_TYPES.put("png", "image/png");
		MIME_TYPES.put("json", "application/json; charset=UTF-8");
	}

	/** 端口被占用时最多往后顺延多少个端口 */
	public static final int MAX_PORT_ATTEMPTS = 64;

	/**
	 * 启动 HTTP 服务器。
	 *
	 * <p>配置端口被别的程序（或上一次没退干净的实例）占用时，自动往后顺延到第一个
	 * 可用端口，避免整个地图服务起不来。返回实际绑定成功的端口。
	 */
	public static int start(int preferredPort, MinecraftServer mcServer) throws IOException {
		minecraftServer = mcServer;
		IOException lastError = null;
		for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
			int port = preferredPort + attempt;
			if (port > 65535) {
				break;
			}
			HttpServer created;
			try {
				created = HttpServer.create(new InetSocketAddress(port), 0);
			} catch (IOException e) {
				// 端口被占用：试下一个
				lastError = e;
				continue;
			}
			server = created;
			registerContexts();
			server.setExecutor(Executors.newCachedThreadPool());
			server.start();
			return port;
		}
		throw lastError != null ? lastError : new IOException("没有可用端口");
	}

	/** 注册全部静态资源与 API 端点 */
	private static void registerContexts() {
		// 静态文件
		server.createContext("/", new StaticFileHandler("/assets/mtrmap/web/index.html", "html"));
		server.createContext("/style.css", new StaticFileHandler("/assets/mtrmap/web/style.css", "css"));
		server.createContext("/map.js", new StaticFileHandler("/assets/mtrmap/web/map.js", "js"));

		// API 端点
		server.createContext("/api/data", exchange -> {
			try {
				JsonObject data = MapDataCollector.collect(minecraftServer);
				sendJson(exchange, GSON.toJson(data));
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.error("处理 /api/data 请求时发生异常", t);
				JsonObject err = new JsonObject();
				err.addProperty("error", true);
				err.addProperty("message", t.getClass().getName() + ": " + t.getMessage());
				sendJson(exchange, GSON.toJson(err));
			}
		});
		server.createContext("/api/players", exchange -> {
			try {
				sendJson(exchange, GSON.toJson(PlayerTracker.toJson()));
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.error("处理 /api/players 请求时发生异常", t);
				JsonObject err = new JsonObject();
				err.addProperty("error", true);
				err.addProperty("message", t.getClass().getName() + ": " + t.getMessage());
				sendJson(exchange, GSON.toJson(err));
			}
		});

		// 线网几何端点：供 Xaero 地图叠加层使用（不含列车，体积更小、开销更低）
		server.createContext("/api/overlay", exchange -> {
			try {
				sendJson(exchange, GSON.toJson(MapDataCollector.collect(minecraftServer, false)));
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.error("处理 /api/overlay 请求时发生异常", t);
				JsonObject err = new JsonObject();
				err.addProperty("error", true);
				err.addProperty("message", t.getClass().getName() + ": " + t.getMessage());
				sendJson(exchange, GSON.toJson(err));
			}
		});

		// 识别"当前网页是谁打开的"：按请求来源 IP 匹配在线玩家；
		// 匹配不到时若只有一名玩家在线就用他；否则返回"您未在游戏中"。
		server.createContext("/api/whoami", exchange -> {
			try {
				PlayerTracker.PlayerInfo player = identifyPlayer(exchange);
				JsonObject out = new JsonObject();
				if (player == null) {
					out.addProperty("ok", false);
					out.addProperty("error", "not_in_game");
				} else {
					out.addProperty("ok", true);
					out.addProperty("uuid", player.uuid);
					out.addProperty("name", player.name);
					out.addProperty("x", player.x);
					out.addProperty("z", player.z);
				}
				sendJson(exchange, GSON.toJson(out));
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.error("处理 /api/whoami 请求时发生异常", t);
				sendError(exchange, t);
			}
		});

		// 导航任务：网页 POST 下发（{uuid, task}），游戏客户端 GET 轮询领取（?uuid=）
		server.createContext("/api/nav", exchange -> {
			try {
				if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
					JsonObject body = readJsonBody(exchange);
					String uuid = body != null && body.has("uuid") ? body.get("uuid").getAsString() : null;
					JsonObject task = body != null && body.has("task") && body.get("task").isJsonObject()
							? body.getAsJsonObject("task") : null;
					NavTaskStore.put(uuid, task);
					JsonObject out = new JsonObject();
					out.addProperty("ok", true);
					sendJson(exchange, GSON.toJson(out));
				} else {
					String uuid = queryParam(exchange, "uuid");
					JsonObject task = NavTaskStore.take(uuid);
					sendJson(exchange, task == null ? "{}" : GSON.toJson(task));
				}
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.error("处理 /api/nav 请求时发生异常", t);
				sendError(exchange, t);
			}
		});

		// 行程记录：GET 查询（?uuid=）、POST 新增
		server.createContext("/api/trips", exchange -> {
			try {
				if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
					JsonObject body = readJsonBody(exchange);
					JsonObject saved = TripStore.add(body);
					JsonObject out = new JsonObject();
					out.addProperty("ok", saved != null);
					if (saved != null) {
						out.add("trip", saved);
					}
					sendJson(exchange, GSON.toJson(out));
				} else {
					String uuid = queryParam(exchange, "uuid");
					sendJson(exchange, GSON.toJson(TripStore.list(uuid)));
				}
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.error("处理 /api/trips 请求时发生异常", t);
				sendError(exchange, t);
			}
		});

		// 删除行程：POST {uuid, id}
		server.createContext("/api/trips/delete", exchange -> {
			try {
				JsonObject body = readJsonBody(exchange);
				String uuid = body != null && body.has("uuid") ? body.get("uuid").getAsString() : null;
				String id = body != null && body.has("id") ? body.get("id").getAsString() : null;
				JsonObject out = new JsonObject();
				out.addProperty("ok", TripStore.delete(uuid, id));
				sendJson(exchange, GSON.toJson(out));
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.error("处理 /api/trips/delete 请求时发生异常", t);
				sendError(exchange, t);
			}
		});

		// 调试端点：线路寻路诊断（排查线位问题时使用）
		server.createContext("/api/debug", exchange -> {
			try {
				sendJson(exchange, GSON.toJson(MapDataCollector.debug(minecraftServer)));
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.error("处理 /api/debug 请求时发生异常", t);
				JsonObject err = new JsonObject();
				err.addProperty("error", true);
				err.addProperty("message", t.getClass().getName() + ": " + t.getMessage());
				sendJson(exchange, GSON.toJson(err));
			}
		});

		// 头像端点
		server.createContext("/avatar/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			// /avatar/{uuid}.png 或 /avatar/{uuid}
			String uuidPart = path.substring("/avatar/".length());
			if (uuidPart.endsWith(".png")) {
				uuidPart = uuidPart.substring(0, uuidPart.length() - 4);
			}
			byte[] png = AvatarHandler.getAvatar(minecraftServer, uuidPart);
			if (png == null || png.length == 0) {
				exchange.sendResponseHeaders(404, -1);
			} else {
				exchange.getResponseHeaders().set("Content-Type", "image/png");
				exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
				exchange.sendResponseHeaders(200, png.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(png);
				}
			}
			exchange.close();
		});
	}

	public static void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
	}

	private static void sendJson(HttpExchange exchange, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
		exchange.close();
	}

	/** 统一的错误 JSON 响应 */
	private static void sendError(HttpExchange exchange, Throwable t) throws IOException {
		JsonObject err = new JsonObject();
		err.addProperty("error", true);
		err.addProperty("message", t.getClass().getName() + ": " + t.getMessage());
		sendJson(exchange, GSON.toJson(err));
	}

	/**
	 * 判定本次 HTTP 请求来自哪个在线玩家：
	 * 1) 请求来源 IP 与某在线玩家的连接 IP 一致；
	 * 2) 否则若只有一名玩家在线，就用他；
	 * 3) 都不满足返回 null（网页显示"您未在游戏中"）。
	 */
	private static PlayerTracker.PlayerInfo identifyPlayer(HttpExchange exchange) {
		InetSocketAddress remote = exchange.getRemoteAddress();
		String ip = remote == null ? null : PlayerTracker.normalize(remote.getAddress());
		PlayerTracker.PlayerInfo player = PlayerTracker.findByIp(ip);
		if (player == null) {
			player = PlayerTracker.sole();
		}
		return player;
	}

	/** 读取请求体并解析为 JSON 对象；为空或非法时返回 null */
	private static JsonObject readJsonBody(HttpExchange exchange) {
		try (InputStream is = exchange.getRequestBody()) {
			String body = new String(MtrMapCommon.readAll(is), StandardCharsets.UTF_8);
			if (body.isEmpty()) {
				return null;
			}
			return MtrMapCommon.parseJson(body).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	/** 取 URL 查询参数（已做 URL 解码） */
	private static String queryParam(HttpExchange exchange, String name) {
		String query = exchange.getRequestURI().getQuery();
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

	/**
	 * 从 classpath 读取静态资源文件并返回。
	 */
	static class StaticFileHandler implements HttpHandler {

		private final String resourcePath;
		private final String type;

		StaticFileHandler(String resourcePath, String type) {
			this.resourcePath = resourcePath;
			this.type = type;
		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
				if (is == null) {
					String msg = "资源未找到: " + resourcePath;
					byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(404, bytes.length);
					try (OutputStream os = exchange.getResponseBody()) {
						os.write(bytes);
					}
				} else {
					byte[] bytes = MtrMapCommon.readAll(is);
					String mime = MIME_TYPES.getOrDefault(type, "application/octet-stream");
					exchange.getResponseHeaders().set("Content-Type", mime);
					exchange.getResponseHeaders().set("Cache-Control", "no-cache");
					exchange.sendResponseHeaders(200, bytes.length);
					try (OutputStream os = exchange.getResponseBody()) {
						os.write(bytes);
					}
				}
			}
			exchange.close();
		}
	}
}
