package com.mtrmap.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 追踪所有在线玩家的位置、名称、UUID、来源 IP。
 * 数据由服务器主线程在每个 tick 末更新，由 HTTP 线程读取。
 *
 * <p>IP 用于网页地图识别"是哪个玩家打开了网页"：浏览器请求的远端地址与该玩家
 * 的连接远端地址一致时，即可认定是同一台机器上的同一个人。
 */
public class PlayerTracker {

	private static final Map<UUID, PlayerInfo> PLAYERS = new ConcurrentHashMap<>();

	/** 归一化后的"本机"标识：127.0.0.1 与 ::1 视为同一个地址 */
	public static final String LOOPBACK = "loopback";

	/**
	 * 每个 tick 末调用：刷新在线玩家列表与坐标。
	 */
	public static void update(MinecraftServer server) {
		if (server == null) {
			return;
		}
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		// 清理已离线玩家
		PLAYERS.keySet().removeIf(uuid -> {
			for (ServerPlayer p : players) {
				if (p.getUUID().equals(uuid)) {
					return false;
				}
			}
			return true;
		});
		// 更新在线玩家
		for (ServerPlayer player : players) {
			PlayerInfo info = new PlayerInfo();
			info.uuid = player.getUUID().toString();
			info.name = player.getName().getString();
			info.x = player.getX();
			info.y = player.getY();
			info.z = player.getZ();
			// Entity.getYRot() 是 1.17 才加的；1.16.5 直接读公有字段 yRot
			//? if >=1.17 {
			info.yaw = player.getYRot();
			//?} else {
			/*info.yaw = player.yRot;
			*///?}
			info.ip = remoteIp(player);
			PLAYERS.put(player.getUUID(), info);
		}
	}

	/**
	 * 读取玩家的连接远端地址并归一化。
	 * 用反射而不是直接调用：{@code ServerGamePacketListenerImpl#getRemoteAddress}
	 * 在不同版本上的继承关系有差异，反射可以在拿不到时安静地返回 null，不至于编译/运行期报错。
	 */
	private static String remoteIp(ServerPlayer player) {
		try {
			Object conn = player.connection;
			if (conn == null) {
				return null;
			}
			Method m = conn.getClass().getMethod("getRemoteAddress");
			Object addr = m.invoke(conn);
			if (addr instanceof InetSocketAddress) {
				return normalize(((InetSocketAddress) addr).getAddress());
			}
			if (addr instanceof SocketAddress) {
				return null;
			}
		} catch (Throwable t) {
			// 拿不到 IP 时静默降级
		}
		return null;
	}

	/** 归一化 IP：环回地址统一成 {@link #LOOPBACK}，避免 127.0.0.1 与 ::1 对不上 */
	public static String normalize(InetAddress address) {
		if (address == null) {
			return null;
		}
		return address.isLoopbackAddress() ? LOOPBACK : address.getHostAddress();
	}

	/** 按 UUID 取玩家信息，找不到返回 null */
	public static PlayerInfo getByUuid(String uuid) {
		if (uuid == null) {
			return null;
		}
		for (Map.Entry<UUID, PlayerInfo> e : PLAYERS.entrySet()) {
			if (e.getKey().toString().equalsIgnoreCase(uuid)) {
				return e.getValue();
			}
		}
		return null;
	}

	/** 按来源 IP 找玩家；可能有多个（同一台机器多开），返回第一个 */
	public static PlayerInfo findByIp(String ip) {
		if (ip == null) {
			return null;
		}
		for (PlayerInfo info : PLAYERS.values()) {
			if (ip.equals(info.ip)) {
				return info;
			}
		}
		return null;
	}

	/** 在线玩家人数 */
	public static int count() {
		return PLAYERS.size();
	}

	/** 仅有一名在线玩家时返回他，否则返回 null */
	public static PlayerInfo sole() {
		return PLAYERS.size() == 1 ? PLAYERS.values().iterator().next() : null;
	}

	public static void clear() {
		PLAYERS.clear();
	}

	/**
	 * 返回玩家列表的 JSON 数组。
	 */
	public static JsonArray toJson() {
		JsonArray array = new JsonArray();
		for (PlayerInfo info : PLAYERS.values()) {
			JsonObject obj = new JsonObject();
			obj.addProperty("uuid", info.uuid);
			obj.addProperty("name", info.name);
			obj.addProperty("x", info.x);
			obj.addProperty("y", info.y);
			obj.addProperty("z", info.z);
			obj.addProperty("yaw", info.yaw);
			array.add(obj);
		}
		return array;
	}

	public static java.util.Collection<PlayerInfo> getAll() {
		return PLAYERS.values();
	}

	public static class PlayerInfo {
		public String uuid;
		public String name;
		public double x;
		public double y;
		public double z;
		public float yaw;
		/** 归一化后的来源 IP（环回统一为 {@link #LOOPBACK}），可能为 null */
		public String ip;
	}
}
