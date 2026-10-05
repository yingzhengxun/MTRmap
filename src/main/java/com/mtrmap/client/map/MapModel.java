package com.mtrmap.client.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏内地图窗口的数据模型，字段与网页 /api/data、/api/players 一一对应。
 *
 * <p>坐标一律用 MTR 的世界坐标（x = 东、z = 南，1 格 = 1 米），
 * 与网页 map.js 的 worldToCanvas 保持同一套语义。
 */
public final class MapModel {

	/** 车站 */
	public static final class Station {
		public long id;
		public String name = "";
		public int color = 0xFFFFFFFF;
		public double x;
		public double z;
		/** 经过该站的线路条数，>=2 即换乘站 */
		public int lines;
		/** 换乘站才下发：全部站台中心的包围盒（minX, minZ, maxX, maxZ） */
		public Double bx1;
		public Double bz1;
		public Double bx2;
		public Double bz2;

		public boolean isInterchange() {
			return lines >= 2;
		}

		public boolean hasBounds() {
			return bx1 != null && bz1 != null && bx2 != null && bz2 != null;
		}
	}

	/** 线路（上行/下行已按主名合并为一条） */
	public static final class Route {
		public long id;
		public String name = "";
		public int color = 0xFFFFFFFF;
		/** 发车间隔（分钟），0 表示未知 */
		public int headway;
		public final List<Long> stations = new ArrayList<>();
		/** 相邻两站之间的真实线位折线，索引与 stations 的相邻对对应 */
		public final List<List<double[]>> paths = new ArrayList<>();
	}

	/** 车厂 */
	public static final class Depot {
		public long id;
		public String name = "";
		public int color = 0xFFFFFFFF;
		public double x1;
		public double z1;
		public double x2;
		public double z2;
		public double centerX;
		public double centerZ;
		public long firstStationId = -1;
		public Double firstPlatformX;
		public Double firstPlatformZ;
		/** 站台到车厂边界的真实轨道连线 */
		public List<double[]> path;
	}

	/** 列车 */
	public static final class Train {
		public long id;
		public double x;
		public double z;
		public int routeColor = 0xFFFF79C6;
		public String routeName = "";
	}

	/** 在线玩家 */
	public static final class Player {
		public String uuid = "";
		public String name = "";
		public double x;
		public double z;
	}

	public final List<Station> stations = new ArrayList<>();
	public final List<Route> routes = new ArrayList<>();
	public final List<Depot> depots = new ArrayList<>();
	public final List<Train> trains = new ArrayList<>();
	public final List<Player> players = new ArrayList<>();
	public final Map<Long, Station> stationById = new HashMap<>();

	/** 服务端配置的「显示车厂」默认值（仅作初始值，之后由窗口内的开关控制） */
	public boolean showDepots = true;
	/** 数据是否已成功加载过一次 */
	public boolean loaded;

	public Station station(long id) {
		return stationById.get(id);
	}

	// ===== 解析 =====

	/**
	 * 从 /api/data 构造一份完整快照。
	 *
	 * <p>模型是「一次构造、之后只读」的：轮询线程每次拉回数据就整体换一个新实例，
	 * 渲染线程拿到的引用始终是一份完整、不会再被改动的数据，两边不需要加锁。
	 */
	public static MapModel fromData(JsonObject root) {
		MapModel model = new MapModel();
		if (root == null) {
			return model;
		}
		model.showDepots = !root.has("showDepots") || root.get("showDepots").getAsBoolean();
		model.parseStations(root);
		model.parseRoutes(root);
		model.parseDepots(root);
		model.parseTrains(root);
		model.loaded = true;
		return model;
	}

	/** 复制一份并替换玩家列表（玩家刷新更频繁，单独换一份快照） */
	public MapModel withPlayers(JsonArray array) {
		MapModel copy = new MapModel();
		copy.stations.addAll(stations);
		copy.routes.addAll(routes);
		copy.depots.addAll(depots);
		copy.trains.addAll(trains);
		copy.stationById.putAll(stationById);
		copy.showDepots = showDepots;
		copy.loaded = loaded;
		copy.parsePlayers(array);
		return copy;
	}

	private void parseStations(JsonObject root) {
		for (JsonElement element : array(root, "stations")) {
			JsonObject obj = element.getAsJsonObject();
			Station st = new Station();
			st.id = longOf(obj, "id", 0);
			st.name = stringOf(obj, "name", "");
			st.color = colorOf(obj, "color");
			st.x = doubleOf(obj, "x", 0);
			st.z = doubleOf(obj, "z", 0);
			st.lines = (int) longOf(obj, "lines", 0);
			if (obj.has("bx1")) {
				st.bx1 = doubleOf(obj, "bx1", 0);
				st.bz1 = doubleOf(obj, "bz1", 0);
				st.bx2 = doubleOf(obj, "bx2", 0);
				st.bz2 = doubleOf(obj, "bz2", 0);
			}
			stations.add(st);
			stationById.put(st.id, st);
		}
	}

	private void parseRoutes(JsonObject root) {
		for (JsonElement element : array(root, "routes")) {
			JsonObject obj = element.getAsJsonObject();
			Route route = new Route();
			route.id = longOf(obj, "id", 0);
			route.name = stringOf(obj, "name", "");
			route.color = colorOf(obj, "color");
			route.headway = (int) longOf(obj, "headway", 0);
			for (JsonElement sid : array(obj, "stations")) {
				route.stations.add(sid.getAsLong());
			}
			for (JsonElement seg : array(obj, "paths")) {
				route.paths.add(polyline(seg.getAsJsonArray()));
			}
			routes.add(route);
		}
	}

	private void parseDepots(JsonObject root) {
		for (JsonElement element : array(root, "depots")) {
			JsonObject obj = element.getAsJsonObject();
			Depot depot = new Depot();
			depot.id = longOf(obj, "id", 0);
			depot.name = stringOf(obj, "name", "");
			depot.color = colorOf(obj, "color");
			depot.x1 = doubleOf(obj, "x1", 0);
			depot.z1 = doubleOf(obj, "z1", 0);
			depot.x2 = doubleOf(obj, "x2", 0);
			depot.z2 = doubleOf(obj, "z2", 0);
			depot.centerX = doubleOf(obj, "centerX", (depot.x1 + depot.x2) / 2);
			depot.centerZ = doubleOf(obj, "centerZ", (depot.z1 + depot.z2) / 2);
			depot.firstStationId = longOf(obj, "firstStationId", -1);
			if (obj.has("firstPlatformX")) {
				depot.firstPlatformX = doubleOf(obj, "firstPlatformX", 0);
				depot.firstPlatformZ = doubleOf(obj, "firstPlatformZ", 0);
			}
			if (obj.has("path")) {
				depot.path = polyline(obj.getAsJsonArray("path"));
			}
			depots.add(depot);
		}
	}

	private void parseTrains(JsonObject root) {
		for (JsonElement element : array(root, "trains")) {
			JsonObject obj = element.getAsJsonObject();
			Train train = new Train();
			train.id = longOf(obj, "id", 0);
			train.x = doubleOf(obj, "x", 0);
			train.z = doubleOf(obj, "z", 0);
			train.routeColor = colorOf(obj, "routeColor");
			train.routeName = stringOf(obj, "routeName", "");
			trains.add(train);
		}
	}

	private void parsePlayers(JsonArray array) {
		if (array == null) {
			return;
		}
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject obj = element.getAsJsonObject();
			Player player = new Player();
			player.uuid = stringOf(obj, "uuid", "");
			player.name = stringOf(obj, "name", "");
			player.x = doubleOf(obj, "x", 0);
			player.z = doubleOf(obj, "z", 0);
			players.add(player);
		}
	}

	private static JsonArray array(JsonObject obj, String key) {
		JsonElement element = obj.get(key);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
	}

	private static List<double[]> polyline(JsonArray array) {
		List<double[]> points = new ArrayList<>();
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject obj = element.getAsJsonObject();
			points.add(new double[]{doubleOf(obj, "x", 0), doubleOf(obj, "z", 0)});
		}
		return points;
	}

	private static String stringOf(JsonObject obj, String key, String fallback) {
		JsonElement element = obj.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static double doubleOf(JsonObject obj, String key, double fallback) {
		JsonElement element = obj.get(key);
		try {
			return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static long longOf(JsonObject obj, String key, long fallback) {
		JsonElement element = obj.get(key);
		try {
			return element == null || element.isJsonNull() ? fallback : element.getAsLong();
		} catch (Exception e) {
			return fallback;
		}
	}

	/** 颜色统一按 ARGB 存；服务端下发的是 0xRRGGBB 或带 alpha 的整数 */
	private static int colorOf(JsonObject obj, String key) {
		JsonElement element = obj.get(key);
		if (element == null || element.isJsonNull()) {
			return 0xFFFFFFFF;
		}
		try {
			int value = element.getAsInt();
			// 没有 alpha 位（高 8 位为 0）时补成不透明
			return (value >>> 24) == 0 ? (value | 0xFF000000) : value;
		} catch (Exception e) {
			return 0xFFFFFFFF;
		}
	}

	/** 网页 splitName：按 '|' 拆成主名称与外语名 */
	public static String[] splitName(String name) {
		String raw = name == null ? "" : name;
		int idx = raw.indexOf('|');
		if (idx < 0) {
			return new String[]{raw.trim(), ""};
		}
		return new String[]{raw.substring(0, idx).trim(), raw.substring(idx + 1).trim()};
	}
}
