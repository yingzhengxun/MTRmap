package com.mtrmap.client.xaero;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.config.MtrMapConfig;
import com.mtrmap.platform.Platform;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端线网数据缓存（供 Xaero 地图叠加层使用）。
 *
 * MTR 的线网数据保存在服务端 RailwayData 中，客户端本身拿不到。
 * 这里在后台线程定期从本机 HTTP 服务拉取 /api/overlay（车站 / 线路折线 / 车厂），
 * 解析成扁平化的浮点数组后放进一个不可变快照，渲染线程只读快照，不做任何同步。
 *
 * 之所以复用 HTTP 而不是自定义网络包：与网页地图共用同一套数据与配置端口，
 * 无需新增服务端逻辑；单人/局域网（也就是本模组的主要场景）下 localhost 一定可达。
 */
public final class OverlayData {

	/** 轮询间隔（毫秒）：线网几何变化不频繁，5 秒足够 */
	private static final long POLL_INTERVAL_MS = 5000L;
	/** 单次请求超时（毫秒） */
	private static final int TIMEOUT_MS = 3000;

	/** 车站：名称 + 地图坐标 + MTR 颜色（ARGB）+ 是否换乘站 */
	public static final class Station {
		public final float x;
		public final float z;
		public final String name;
		public final int color;
		/** 被 2 条及以上线路经过，绘制时改用跑道形标记 */
		public final boolean interchange;
		/**
		 * 该站全部站台中心的包围盒 (minX, minZ, maxX, maxZ)；
		 * 跑道形标记的长度与朝向由它决定，普通车站为 null。
		 */
		public final float[] bounds;

		Station(float x, float z, String name, int color, boolean interchange, float[] bounds) {
			this.x = x;
			this.z = z;
			this.name = name;
			this.color = color;
			this.interchange = interchange;
			this.bounds = bounds;
		}
	}

	/**
	 * 线路：一条按主名合并后的单线。
	 * 折线以扁平数组存放（xs[i], zs[i] 为一个点），避免大量小对象。
	 * brk[i] 为 true 表示"从 i 点开始是一条新的子折线"（上一段与这一段之间没有轨道相连，
	 * 不能把两点直接连起来，否则地图上会出现穿过地图的假线）。
	 */
	public static final class Line {
		public final int color;
		public final float[] xs;
		public final float[] zs;
		public final boolean[] brk;

		Line(int color, float[] xs, float[] zs, boolean[] brk) {
			this.color = color;
			this.xs = xs;
			this.zs = zs;
			this.brk = brk;
		}
	}

	/** 车厂：矩形范围 + 与第一个车站之间的连接线（可能为空） */
	public static final class Depot {
		public final int color;
		public final float x1;
		public final float z1;
		public final float x2;
		public final float z2;
		public final float[] linkXs;
		public final float[] linkZs;

		Depot(int color, float x1, float z1, float x2, float z2, float[] linkXs, float[] linkZs) {
			this.color = color;
			this.x1 = x1;
			this.z1 = z1;
			this.x2 = x2;
			this.z2 = z2;
			this.linkXs = linkXs;
			this.linkZs = linkZs;
		}
	}

	/** 一次完整的数据快照，整体替换，渲染时读取引用即可 */
	public static final class Snapshot {
		public static final Snapshot EMPTY = new Snapshot(new Station[0], new Line[0], new Depot[0]);

		public final Station[] stations;
		public final Line[] lines;
		public final Depot[] depots;

		Snapshot(Station[] stations, Line[] lines, Depot[] depots) {
			this.stations = stations;
			this.lines = lines;
			this.depots = depots;
		}
	}

	private static volatile Snapshot snapshot = Snapshot.EMPTY;
	private static volatile boolean started = false;

	public static Snapshot get() {
		return snapshot;
	}

	/** 启动后台轮询线程（只会启动一次） */
	public static void start() {
		if (started) {
			return;
		}
		// 没有装 Xaero 地图就没必要轮询，省掉每 5 秒一次的 HTTP 请求
		if (!isXaeroPresent()) {
			return;
		}
		started = true;
		Thread thread = new Thread(OverlayData::loop, "MTRMap-XaeroOverlay");
		thread.setDaemon(true);
		thread.start();
	}

	private static boolean isXaeroPresent() {
		try {
			return Platform.isModLoaded("xaeroworldmap");
		} catch (Throwable t) {
			return false;
		}
	}

	private static void loop() {
		while (true) {
			try {
				Snapshot fetched = fetch();
				if (fetched != null) {
					snapshot = fetched;
				}
			} catch (Throwable t) {
				// 服务端还没启动 / 端口未监听时静默重试，不刷屏
			}
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private static Snapshot fetch() throws Exception {
		int port = MtrMapConfig.getActivePort();
		HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/overlay").openConnection();
		try {
			conn.setConnectTimeout(TIMEOUT_MS);
			conn.setReadTimeout(TIMEOUT_MS);
			conn.setRequestMethod("GET");
			if (conn.getResponseCode() != 200) {
				return null;
			}
			String body;
			try (InputStream is = conn.getInputStream()) {
				body = new String(MtrMapCommon.readAll(is), StandardCharsets.UTF_8);
			}
			return parse(body);
		} finally {
			conn.disconnect();
		}
	}

	private static Snapshot parse(String json) {
		JsonElement root = MtrMapCommon.parseJson(json);
		if (!root.isJsonObject()) {
			return null;
		}
		JsonObject obj = root.getAsJsonObject();

		// 车站
		List<Station> stations = new ArrayList<>();
		for (JsonElement e : getArray(obj, "stations")) {
			JsonObject s = e.getAsJsonObject();
			boolean interchange = getInt(s, "lines") >= 2;
			float[] bounds = null;
			if (interchange && s.has("bx1") && s.has("bz1") && s.has("bx2") && s.has("bz2")) {
				bounds = new float[]{
						getFloat(s, "bx1"), getFloat(s, "bz1"),
						getFloat(s, "bx2"), getFloat(s, "bz2")
				};
			}
			stations.add(new Station(
					getFloat(s, "x"),
					getFloat(s, "z"),
					getString(s, "name"),
					getInt(s, "color"),
					interchange,
					bounds));
		}

		// 线路：把每条线路的若干段折线拼成一条数组。
		// 相邻两段在站台中心点处首尾重合，重合点去重后不算断点；否则记为断点。
		List<Line> lines = new ArrayList<>();
		for (JsonElement e : getArray(obj, "routes")) {
			JsonObject r = e.getAsJsonObject();
			List<float[]> pts = new ArrayList<>();
			List<Boolean> brk = new ArrayList<>();
			for (JsonElement pe : getArray(r, "paths")) {
				if (!pe.isJsonArray()) {
					continue;
				}
				JsonArray seg = pe.getAsJsonArray();
				boolean first = true;
				for (JsonElement qe : seg) {
					if (!qe.isJsonObject()) {
						continue;
					}
					JsonObject q = qe.getAsJsonObject();
					float px = getFloat(q, "x");
					float pz = getFloat(q, "z");
					if (first) {
						first = false;
						int last = pts.size() - 1;
						if (last >= 0 && pts.get(last)[0] == px && pts.get(last)[1] == pz) {
							// 与上一段末点重合，直接跳过，保持折线连续
							continue;
						}
						// 新子折线的起点
						pts.add(new float[]{px, pz});
						brk.add(true);
					} else {
						pts.add(new float[]{px, pz});
						brk.add(false);
					}
				}
			}
			if (pts.size() < 2) {
				continue;
			}
			float[] xs = new float[pts.size()];
			float[] zs = new float[pts.size()];
			boolean[] breaks = new boolean[pts.size()];
			for (int i = 0; i < pts.size(); i++) {
				xs[i] = pts.get(i)[0];
				zs[i] = pts.get(i)[1];
				breaks[i] = brk.get(i);
			}
			// 首点永远是一条子折线的起点
			breaks[0] = true;
			lines.add(new Line(getInt(r, "color"), xs, zs, breaks));
		}

		// 车厂
		List<Depot> depots = new ArrayList<>();
		for (JsonElement e : getArray(obj, "depots")) {
			JsonObject d = e.getAsJsonObject();
			if (!d.has("x1") || !d.has("x2")) {
				continue;
			}
			float[] lx = null;
			float[] lz = null;
			if (d.has("path") && d.get("path").isJsonArray()) {
				JsonArray path = d.getAsJsonArray("path");
				lx = new float[path.size()];
				lz = new float[path.size()];
				for (int i = 0; i < path.size(); i++) {
					JsonObject q = path.get(i).getAsJsonObject();
					lx[i] = getFloat(q, "x");
					lz[i] = getFloat(q, "z");
				}
			}
			depots.add(new Depot(
					getInt(d, "color"),
					getFloat(d, "x1"), getFloat(d, "z1"),
					getFloat(d, "x2"), getFloat(d, "z2"),
					lx, lz));
		}

		return new Snapshot(stations.toArray(new Station[0]), lines.toArray(new Line[0]),
				depots.toArray(new Depot[0]));
	}

	private static JsonArray getArray(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	private static float getFloat(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e == null || e.isJsonNull() ? 0f : e.getAsFloat();
	}

	private static int getInt(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e == null || e.isJsonNull() ? 0 : e.getAsInt();
	}

	private static String getString(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}
}
