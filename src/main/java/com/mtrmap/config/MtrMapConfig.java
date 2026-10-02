package com.mtrmap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MTR 地图模组配置：控制是否显示车厂、HTTP 服务端口等选项。
 * 配置文件位于游戏 mods 目录下的 mapconfig/mtrmap.json，
 * 目录或文件不存在时自动创建。
 */
public class MtrMapConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path configPath;

	private static Path getConfigPath() {
		if (configPath == null) {
			configPath = Platform.getConfigDir().resolve("mods/mapconfig/mtrmap.json");
		}
		return configPath;
	}

	private static ConfigData data = new ConfigData();

	public static boolean isShowDepots() {
		return data.showDepots;
	}

	public static void setShowDepots(boolean value) {
		data.showDepots = value;
	}

	/** HTTP 服务器端口（默认 1145） */
	public static int getPort() {
		return data.port;
	}

	/**
	 * Xaero 世界地图叠加层（在地图上显示 MTR 线网）是否开启。
	 * 默认开启；世界地图右侧的开关按钮会改写这个值并保存。
	 */
	public static boolean isXaeroOverlay() {
		return data.xaeroOverlay;
	}

	public static void setXaeroOverlay(boolean value) {
		data.xaeroOverlay = value;
	}

	public static void load() {
		Path path = getConfigPath();
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
		} catch (IOException e) {
			MtrMapCommon.LOGGER.warn("创建配置目录失败", e);
		}
		if (Files.exists(path)) {
			try {
				// Files.readString/writeString 是 Java 11 才加的，1.16.5 编译目标是 Java 8
				String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
				ConfigData loaded = GSON.fromJson(content, ConfigData.class);
				if (loaded != null) {
					data = loaded;
				}
			} catch (Exception e) {
				MtrMapCommon.LOGGER.warn("读取 mtrmap.json 失败，使用默认配置", e);
			}
		} else {
			save();
		}
	}

	public static void save() {
		try {
			Path path = getConfigPath();
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.write(path, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			MtrMapCommon.LOGGER.error("保存 mtrmap.json 失败", e);
		}
	}

	public static class ConfigData {
		public boolean showDepots = true;
		public int port = 1145;
		/** 是否在 Xaero 世界地图上叠加 MTR 线网 */
		public boolean xaeroOverlay = true;
	}
}
