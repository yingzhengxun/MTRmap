package com.mtrmap.server;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导航任务暂存区。
 *
 * <p>网页地图把"给某个玩家下发的导航任务"（起点、终点、途经步骤等）POST 到这里，
 * 游戏客户端每秒轮询一次领取（领取即取走）。任务只在内存中保留，不落盘：
 * 关掉游戏或重启服务器后未领取的任务自然失效是合理的。
 */
public final class NavTaskStore {

	/** 每个玩家最多保留一条待领取任务 */
	private static final Map<String, JsonObject> PENDING = new ConcurrentHashMap<>();

	private NavTaskStore() {
	}

	/** 网页下发任务，覆盖该玩家尚未领取的旧任务 */
	public static void put(String uuid, JsonObject task) {
		if (uuid == null || task == null) {
			return;
		}
		PENDING.put(uuid.toLowerCase(), task);
	}

	/** 客户端领取任务（取走即删除），没有则返回 null */
	public static JsonObject take(String uuid) {
		if (uuid == null) {
			return null;
		}
		return PENDING.remove(uuid.toLowerCase());
	}

	public static void clear() {
		PENDING.clear();
	}
}
