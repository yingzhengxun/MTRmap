package com.mtrmap.server;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 底图瓦片仓库：存放客户端上传的瓦片（客户端用 Xaero 的地图数据合成），
 * 供网页地图读取，落盘在 {@code <游戏目录>/mtrmap/worldmap-tiles/<zoom>/<x>_<y>.png}。
 *
 * <p>落盘是为了重启后网页仍能立刻看到底图，不必等客户端重新上传。
 */
public final class WorldMapStore {

	/** 内存里保留多少张瓦片（每张几十到几百 KB） */
	private static final int MEMORY_CACHE = 64;

	private static final Map<String, byte[]> TILES =
			Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(MEMORY_CACHE, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
					return size() > MEMORY_CACHE;
				}
			});

	private static volatile Path directory;

	private WorldMapStore() {
	}

	private static Path dir() {
		Path current = directory;
		if (current == null) {
			current = Platform.getConfigDir().resolve("mtrmap").resolve("worldmap-tiles");
			directory = current;
		}
		return current;
	}

	/** 客户端上传：写内存 + 落盘（覆盖旧图） */
	public static void put(String key, byte[] png) {
		if (key == null || png == null || png.length == 0) {
			return;
		}
		TILES.put(key, png);
		try {
			Path file = dir().resolve(key + ".png");
			Files.createDirectories(file.getParent());
			Files.write(file, png);
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.debug("保存底图瓦片失败: {}", key, t);
		}
	}

	/** 取瓦片：先内存，再磁盘；没有返回 null */
	public static byte[] get(String key) {
		byte[] cached = TILES.get(key);
		if (cached != null) {
			return cached;
		}
		try {
			Path file = dir().resolve(key + ".png");
			if (!Files.exists(file)) {
				return null;
			}
			byte[] bytes = Files.readAllBytes(file);
			if (bytes.length == 0) {
				return null;
			}
			TILES.put(key, bytes);
			return bytes;
		} catch (Throwable t) {
			return null;
		}
	}

	/** 已落盘的瓦片数量（给 /api/worldmap/settings 显示用） */
	public static int storedTiles() {
		int inMemory = TILES.size();
		try {
			Path path = dir();
			if (!Files.isDirectory(path)) {
				return inMemory;
			}
			final int[] count = {0};
			try (var stream = Files.walk(path)) {
				stream.filter(Files::isRegularFile).forEach(file -> count[0]++);
			}
			return count[0];
		} catch (Throwable t) {
			return inMemory;
		}
	}
}
