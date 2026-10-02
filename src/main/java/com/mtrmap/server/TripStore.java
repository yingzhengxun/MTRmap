package com.mtrmap.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 行程记录（纪念用）。
 *
 * <p>玩家在游戏内完成或中途退出导航时，客户端把该次行程结果 POST 上来，
 * 这里按玩家 UUID 归档并落盘到 {@code mods/mapconfig/mtrmap_trips.json}。
 * 网页地图的"行程记录"子菜单读取该文件展示，并支持删除。
 *
 * <p>文件结构是一个 JSON 数组，每个元素就是一条行程对象（原样保存客户端提交的字段，
 * 只补齐 id / uuid / time）。
 */
public final class TripStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static Path filePath;
	/** 全部行程，按加入时间从新到旧排列 */
	private static JsonArray trips;
	private static boolean loaded = false;

	private TripStore() {
	}

	private static Path getFilePath() {
		if (filePath == null) {
			filePath = Platform.getConfigDir().resolve("mods/mapconfig/mtrmap_trips.json");
		}
		return filePath;
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		trips = new JsonArray();
		Path path = getFilePath();
		try {
			if (Files.exists(path)) {
				// Files.readString/writeString 是 Java 11 才加的，1.16.5 编译目标是 Java 8
				String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
				JsonElement root = GSON.fromJson(content, JsonElement.class);
				if (root != null && root.isJsonArray()) {
					trips = root.getAsJsonArray();
				}
			}
		} catch (Exception e) {
			MtrMapCommon.LOGGER.warn("读取行程记录失败", e);
		}
	}

	private static synchronized void save() {
		try {
			Path path = getFilePath();
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.write(path, GSON.toJson(trips).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			MtrMapCommon.LOGGER.error("保存行程记录失败", e);
		}
	}

	/** 某玩家的全部行程（从新到旧） */
	public static synchronized JsonArray list(String uuid) {
		ensureLoaded();
		JsonArray out = new JsonArray();
		if (uuid == null) {
			return out;
		}
		String key = uuid.toLowerCase();
		for (JsonElement e : trips) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject t = e.getAsJsonObject();
			if (t.has("uuid") && key.equals(t.get("uuid").getAsString().toLowerCase())) {
				out.add(t);
			}
		}
		return out;
	}

	/** 新增一条行程，返回补齐 id 后的对象；uuid 为空时忽略 */
	public static synchronized JsonObject add(JsonObject trip) {
		ensureLoaded();
		if (trip == null || !trip.has("uuid") || trip.get("uuid").getAsString().isEmpty()) {
			return null;
		}
		if (!trip.has("id")) {
			trip.addProperty("id", UUID.randomUUID().toString());
		}
		if (!trip.has("time")) {
			trip.addProperty("time", System.currentTimeMillis() / 1000L);
		}
		// 新行程放最前面，网页列表天然按时间倒序
		JsonArray next = new JsonArray();
		next.add(trip);
		next.addAll(trips);
		trips = next;
		save();
		return trip;
	}

	/** 删除某玩家的一条行程；返回是否真的删掉了 */
	public static synchronized boolean delete(String uuid, String id) {
		ensureLoaded();
		if (uuid == null || id == null) {
			return false;
		}
		String key = uuid.toLowerCase();
		JsonArray next = new JsonArray();
		boolean removed = false;
		for (JsonElement e : trips) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject t = e.getAsJsonObject();
			boolean mine = t.has("uuid") && key.equals(t.get("uuid").getAsString().toLowerCase());
			boolean match = t.has("id") && id.equals(t.get("id").getAsString());
			if (mine && match) {
				removed = true;
				continue;
			}
			next.add(t);
		}
		if (removed) {
			trips = next;
			save();
		}
		return removed;
	}

	/** 服务器停止时调用：下次读取重新从文件加载，避免持有旧数据 */
	public static synchronized void clear() {
		trips = null;
		loaded = false;
		filePath = null;
	}
}
