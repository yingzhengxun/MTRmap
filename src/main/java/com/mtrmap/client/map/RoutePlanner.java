package com.mtrmap.client.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路径查询：与网页 map.js 完全同一套算法。
 *
 * <p>图节点是「线路:车站」，边分三类：
 * <ul>
 *   <li>ride —— 同一条线路上相邻车站（双向）；</li>
 *   <li>transfer —— 同一车站换乘另一条线（距离 0，代价里计入换乘惩罚）；</li>
 *   <li>walk —— 站外步行换乘：每条本线没有的线路，只连到离本站最近的那一站
 *       （直线距离 ≤ 1 km）；本线已有的线路不建步行边，同站换乘优先。</li>
 * </ul>
 * 三种目标（最短 / 最省时 / 最少换乘）用不同的边权，代价是
 * {@code [主目标, 次目标]} 的字典序。
 */
public final class RoutePlanner {

	/** 假定运营速度 60 km/h */
	private static final double AVG_SPEED_KMH = 60.0;
	/** 1 格 = 1 米 */
	private static final double AVG_SPEED_PER_SEC = AVG_SPEED_KMH / 3.6;
	/** 每经停一站 25 秒 */
	private static final double DWELL_SEC = 25.0;
	/** 每次换乘 180 秒 */
	private static final double TRANSFER_PENALTY_SEC = 180.0;
	/** 站外步行换乘的最大直线距离 */
	private static final double WALK_MAX_METERS = 1000.0;
	/** 步行速度 ≈ 4.3 格/秒（游戏内步行速度） */
	private static final double WALK_SPEED_PER_SEC = 4.3;

	public enum Mode {
		SHORTEST("最短"),
		FASTEST("最省时"),
		FEWEST("最少换乘");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	/** 8 方位：0 = 正北，顺时针 */
	public static final String[] DIRECTIONS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

	/** 方案里的一步：乘车段或步行段 */
	public static final class Step {
		public boolean walk;
		// 乘车段
		public long routeId;
		public String routeName = "";
		public int routeColor = 0xFFFFFFFF;
		public final List<Long> stations = new ArrayList<>();
		/** 该段列车开往的终点站；非线性站序时为 null（不显示方向） */
		public Long terminalId;
		// 步行段
		public long fromStation;
		public long toStation;
		public double dist;
		/** 步行方位（0..7），未知为 null */
		public Integer dirIndex;
	}

	/** 一条候选方案 */
	public static final class Option {
		public Mode mode;
		public final List<Step> steps = new ArrayList<>();
		public double dist;
		public double walkDist;
		public double timeSec;
		public int hops;
		public int transfers;
	}

	/** 起点：从「我的位置」步行到起点站的指引 */
	public static final class StartWalk {
		public double dist;
		public Integer dirIndex;
	}

	private RoutePlanner() {
	}

	// ===== 入口 =====

	/** 查询三种目标的候选方案；无法到达时返回空列表 */
	public static List<Option> compute(MapModel model, long startId, long endId) {
		List<Option> options = new ArrayList<>();
		if (startId == endId || model.station(startId) == null || model.station(endId) == null) {
			return options;
		}
		Metrics metrics = buildMetrics(model);
		Map<String, List<Edge>> adjacency = buildGraph(model, metrics);
		for (Mode mode : Mode.values()) {
			Option option = computeOption(model, metrics, adjacency, mode, startId, endId);
			if (option != null) {
				options.add(option);
			}
		}
		return options;
	}

	/** 从当前位置到起点站的步行指引 */
	public static StartWalk startWalk(MapModel model, double myX, double myZ, long startId) {
		MapModel.Station station = model.station(startId);
		if (station == null) {
			return null;
		}
		StartWalk walk = new StartWalk();
		walk.dist = Math.hypot(station.x - myX, station.z - myZ);
		walk.dirIndex = direction(myX, myZ, station.x, station.z);
		return walk;
	}

	/** 距给定坐标最近的车站 */
	public static MapModel.Station nearestStation(MapModel model, double x, double z) {
		MapModel.Station best = null;
		double bestDist = Double.MAX_VALUE;
		for (MapModel.Station station : model.stations) {
			double d = Math.hypot(station.x - x, station.z - z);
			if (d < bestDist) {
				bestDist = d;
				best = station;
			}
		}
		return best;
	}

	/** 方位索引：atan2(东分量, 北分量)，0 = 正北，顺时针；两点重合返回 null */
	public static Integer direction(double fromX, double fromZ, double toX, double toZ) {
		double dx = toX - fromX;
		double dz = toZ - fromZ;
		if (dx == 0 && dz == 0) {
			return null;
		}
		double angle = Math.atan2(dx, -dz);
		int index = (int) Math.round(angle / (Math.PI / 4));
		return ((index % 8) + 8) % 8;
	}

	// ===== 建图 =====

	private static final class Metrics {
		final Map<String, Double> segDist = new HashMap<>();
		final Map<Long, List<Long>> stationRoutes = new HashMap<>();
		final Map<Long, MapModel.Route> routeById = new HashMap<>();
	}

	private static final class Edge {
		final String to;
		final double dist;
		final Kind kind;

		Edge(String to, double dist, Kind kind) {
			this.to = to;
			this.dist = dist;
			this.kind = kind;
		}
	}

	private enum Kind { RIDE, TRANSFER, WALK }

	private static Metrics buildMetrics(MapModel model) {
		Metrics metrics = new Metrics();
		for (MapModel.Route route : model.routes) {
			metrics.routeById.put(route.id, route);
			List<Long> ids = route.stations;
			for (Long sid : ids) {
				List<Long> list = metrics.stationRoutes.computeIfAbsent(sid, k -> new ArrayList<>());
				if (!list.contains(route.id)) {
					list.add(route.id);
				}
			}
			for (int i = 0; i < ids.size() - 1; i++) {
				long a = ids.get(i);
				long b = ids.get(i + 1);
				double d = segmentLength(route, i, model.station(a), model.station(b));
				metrics.segDist.putIfAbsent(route.id + ":" + a + ":" + b, d);
				metrics.segDist.putIfAbsent(route.id + ":" + b + ":" + a, d);
			}
		}
		return metrics;
	}

	private static double segmentLength(MapModel.Route route, int index, MapModel.Station a, MapModel.Station b) {
		if (index < route.paths.size()) {
			List<double[]> seg = route.paths.get(index);
			if (seg != null && seg.size() >= 2) {
				double len = 0;
				for (int i = 0; i < seg.size() - 1; i++) {
					len += Math.hypot(seg.get(i + 1)[0] - seg.get(i)[0], seg.get(i + 1)[1] - seg.get(i)[1]);
				}
				return len;
			}
		}
		if (a != null && b != null) {
			return Math.hypot(b.x - a.x, b.z - a.z);
		}
		return 0;
	}

	private static Map<String, List<Edge>> buildGraph(MapModel model, Metrics metrics) {
		Map<String, List<Edge>> adjacency = new HashMap<>();
		for (MapModel.Route route : model.routes) {
			List<Long> ids = route.stations;
			for (int i = 0; i < ids.size() - 1; i++) {
				double d = segmentDistance(metrics, route.id, ids.get(i), ids.get(i + 1));
				addEdge(adjacency, route.id + ":" + ids.get(i), route.id + ":" + ids.get(i + 1), d, Kind.RIDE);
				addEdge(adjacency, route.id + ":" + ids.get(i + 1), route.id + ":" + ids.get(i), d, Kind.RIDE);
			}
		}
		for (Map.Entry<Long, List<Long>> entry : metrics.stationRoutes.entrySet()) {
			List<Long> routes = entry.getValue();
			for (int i = 0; i < routes.size(); i++) {
				for (int j = 0; j < routes.size(); j++) {
					if (i == j) {
						continue;
					}
					addEdge(adjacency, routes.get(i) + ":" + entry.getKey(),
							routes.get(j) + ":" + entry.getKey(), 0, Kind.TRANSFER);
				}
			}
		}
		addWalkEdges(model, metrics, adjacency);
		return adjacency;
	}

	private static void addEdge(Map<String, List<Edge>> adjacency, String from, String to, double dist, Kind kind) {
		adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(to, dist, kind));
	}

	private static void addWalkEdges(MapModel model, Metrics metrics, Map<String, List<Edge>> adjacency) {
		// 站外步行换乘：每条「当前站没有的线路」只连到离当前站最近的那个车站。
		// 以前是把 1 km 内的车站两两连起来，规划器可能让人走过一个更近的同线路车站、
		// 跑到更远的站去换乘；现在严格取最近的那一站。
		// 当前站已有的线路不建步行边：同站换乘（transfer 边）本来就能换，不必出站。
		List<MapModel.Station> stations = model.stations;
		for (MapModel.Station a : stations) {
			List<Long> routesA = metrics.stationRoutes.get(a.id);
			if (routesA == null || routesA.isEmpty()) {
				continue;
			}
			// 目标线路 -> 该线路上离 a 最近的车站（[距离, 车站ID]）
			Map<Long, double[]> nearest = new HashMap<>();
			for (MapModel.Station b : stations) {
				if (b.id == a.id) {
					continue;
				}
				double dx = b.x - a.x;
				if (dx < -WALK_MAX_METERS || dx > WALK_MAX_METERS) {
					continue;
				}
				double dz = b.z - a.z;
				if (dz < -WALK_MAX_METERS || dz > WALK_MAX_METERS) {
					continue;
				}
				double d = Math.hypot(dx, dz);
				if (d > WALK_MAX_METERS) {
					continue;
				}
				List<Long> routesB = metrics.stationRoutes.get(b.id);
				if (routesB == null || routesB.isEmpty()) {
					continue;
				}
				for (Long ridB : routesB) {
					if (routesA.contains(ridB)) {
						continue;
					}
					double[] best = nearest.get(ridB);
					if (best == null || d < best[0]) {
						nearest.put(ridB, new double[]{d, b.id});
					}
				}
			}
			for (Map.Entry<Long, double[]> entry : nearest.entrySet()) {
				long ridB = entry.getKey();
				double d = entry.getValue()[0];
				long stationB = (long) entry.getValue()[1];
				for (Long ridA : routesA) {
					addEdge(adjacency, ridA + ":" + a.id, ridB + ":" + stationB, d, Kind.WALK);
					addEdge(adjacency, ridB + ":" + stationB, ridA + ":" + a.id, d, Kind.WALK);
				}
			}
		}
	}

	private static double segmentDistance(Metrics metrics, long routeId, long a, long b) {
		Double d = metrics.segDist.get(routeId + ":" + a + ":" + b);
		return d == null ? 0 : d;
	}

	/** 图节点键 "线路:车站" */
	private static long nodeStation(String key) {
		int idx = key.indexOf(':');
		return Long.parseLong(key.substring(idx + 1));
	}

	private static long nodeRoute(String key) {
		int idx = key.indexOf(':');
		return Long.parseLong(key.substring(0, idx));
	}

	// ===== Dijkstra =====

	/** 代价：[主目标, 次目标]，字典序比较 */
	private static boolean costLess(double[] a, double[] b) {
		if (a[0] != b[0]) {
			return a[0] < b[0];
		}
		return a[1] < b[1];
	}

	private static double[] edgeCost(Mode mode, double dist, Kind kind) {
		if (kind == Kind.TRANSFER) {
			if (mode == Mode.FASTEST) {
				return new double[]{TRANSFER_PENALTY_SEC, 1};
			}
			if (mode == Mode.FEWEST) {
				return new double[]{1, 0};
			}
			return new double[]{0, 1};
		}
		if (kind == Kind.WALK) {
			if (mode == Mode.FASTEST) {
				return new double[]{dist / WALK_SPEED_PER_SEC, 1};
			}
			if (mode == Mode.FEWEST) {
				return new double[]{1, dist};
			}
			return new double[]{dist, 1};
		}
		if (mode == Mode.FASTEST) {
			return new double[]{dist / AVG_SPEED_PER_SEC + DWELL_SEC, 0};
		}
		if (mode == Mode.FEWEST) {
			return new double[]{0, dist};
		}
		return new double[]{dist, 0};
	}

	private static final class Node {
		final String key;
		final double[] cost;

		Node(String key, double[] cost) {
			this.key = key;
			this.cost = cost;
		}
	}

	/** 路径回溯结果 */
	private static final class Path {
		final List<String> nodes = new ArrayList<>();
		final List<Kind> kinds = new ArrayList<>();
	}

	private static Path dijkstra(Metrics metrics, Map<String, List<Edge>> adjacency,
			long startId, long endId, Mode mode) {
		List<Long> startRoutes = metrics.stationRoutes.get(startId);
		if (startRoutes == null || startRoutes.isEmpty()) {
			return null;
		}
		Map<String, double[]> best = new HashMap<>();
		Map<String, String> prevNode = new HashMap<>();
		Map<String, Kind> prevKind = new HashMap<>();
		List<Node> heap = new ArrayList<>();
		for (Long rid : startRoutes) {
			String key = rid + ":" + startId;
			best.put(key, new double[]{0, 0});
			heapPush(heap, new Node(key, new double[]{0, 0}));
		}
		String endKey = null;
		while (!heap.isEmpty()) {
			Node current = heapPop(heap);
			double[] known = best.get(current.key);
			if (known != null && costLess(known, current.cost)) {
				continue;
			}
			if (nodeStation(current.key) == endId) {
				endKey = current.key;
				break;
			}
			List<Edge> edges = adjacency.get(current.key);
			if (edges == null) {
				continue;
			}
			for (Edge edge : edges) {
				double[] add = edgeCost(mode, edge.dist, edge.kind);
				double[] next = {current.cost[0] + add[0], current.cost[1] + add[1]};
				double[] existing = best.get(edge.to);
				if (existing == null || costLess(next, existing)) {
					best.put(edge.to, next);
					prevNode.put(edge.to, current.key);
					prevKind.put(edge.to, edge.kind);
					heapPush(heap, new Node(edge.to, next));
				}
			}
		}
		if (endKey == null) {
			return null;
		}
		Path path = new Path();
		String node = endKey;
		while (node != null) {
			path.nodes.add(0, node);
			String previous = prevNode.get(node);
			if (previous == null) {
				break;
			}
			path.kinds.add(0, prevKind.get(node));
			node = previous;
		}
		return path;
	}

	// 简易二叉堆
	private static void heapPush(List<Node> heap, Node item) {
		heap.add(item);
		int i = heap.size() - 1;
		while (i > 0) {
			int parent = (i - 1) >> 1;
			if (!costLess(heap.get(i).cost, heap.get(parent).cost)) {
				break;
			}
			Node tmp = heap.get(parent);
			heap.set(parent, heap.get(i));
			heap.set(i, tmp);
			i = parent;
		}
	}

	private static Node heapPop(List<Node> heap) {
		Node top = heap.get(0);
		Node last = heap.remove(heap.size() - 1);
		if (!heap.isEmpty()) {
			heap.set(0, last);
			int i = 0;
			while (true) {
				int left = i * 2 + 1;
				int right = left + 1;
				int min = i;
				if (left < heap.size() && costLess(heap.get(left).cost, heap.get(min).cost)) {
					min = left;
				}
				if (right < heap.size() && costLess(heap.get(right).cost, heap.get(min).cost)) {
					min = right;
				}
				if (min == i) {
					break;
				}
				Node tmp = heap.get(min);
				heap.set(min, heap.get(i));
				heap.set(i, tmp);
				i = min;
			}
		}
		return top;
	}

	// ===== 节点序列 → 方案 =====

	private static Option computeOption(MapModel model, Metrics metrics, Map<String, List<Edge>> adjacency,
			Mode mode, long startId, long endId) {
		Path path = dijkstra(metrics, adjacency, startId, endId, mode);
		if (path == null) {
			return null;
		}
		Option option = new Option();
		option.mode = mode;

		List<Step> steps = option.steps;
		Step ride = null;
		for (int i = 0; i < path.nodes.size() - 1; i++) {
			Kind kind = path.kinds.get(i);
			long fromRoute = nodeRoute(path.nodes.get(i));
			long fromStation = nodeStation(path.nodes.get(i));
			long toStation = nodeStation(path.nodes.get(i + 1));
			if (kind == Kind.RIDE) {
				if (ride == null || ride.routeId != fromRoute) {
					ride = new Step();
					ride.routeId = fromRoute;
					ride.stations.add(fromStation);
					steps.add(ride);
				}
				ride.stations.add(toStation);
			} else if (kind == Kind.WALK) {
				ride = null;
				Step walk = new Step();
				walk.walk = true;
				walk.fromStation = fromStation;
				walk.toStation = toStation;
				steps.add(walk);
			} else {
				ride = null;
			}
		}
		if (steps.isEmpty()) {
			return null;
		}
		// 合并相邻步行段（换乘边不产生步骤，会出现「步行到 B 再从 B 步行到 C」）
		for (int i = 0; i < steps.size() - 1; ) {
			if (steps.get(i).walk && steps.get(i + 1).walk) {
				steps.get(i).toStation = steps.get(i + 1).toStation;
				steps.remove(i + 1);
			} else {
				i++;
			}
		}
		// 补全每步的信息
		int legs = 0;
		double dist = 0;
		double walkDist = 0;
		int hops = 0;
		for (Step step : steps) {
			if (step.walk) {
				MapModel.Station a = model.station(step.fromStation);
				MapModel.Station b = model.station(step.toStation);
				step.dist = a != null && b != null ? Math.hypot(b.x - a.x, b.z - a.z) : 0;
				step.dirIndex = a != null && b != null ? direction(a.x, a.z, b.x, b.z) : null;
				walkDist += step.dist;
				continue;
			}
			legs++;
			MapModel.Route route = metrics.routeById.get(step.routeId);
			step.routeName = route != null ? route.name : ("#" + step.routeId);
			step.routeColor = route != null ? route.color : 0xFFFFFFFF;
			step.terminalId = terminalStation(route, step.stations);
			for (int i = 0; i < step.stations.size() - 1; i++) {
				dist += segmentDistance(metrics, step.routeId, step.stations.get(i), step.stations.get(i + 1));
				hops++;
			}
		}
		option.dist = dist + walkDist;
		option.walkDist = walkDist;
		option.hops = hops;
		option.transfers = Math.max(0, legs - 1);
		option.timeSec = dist / AVG_SPEED_PER_SEC + hops * DWELL_SEC
				+ walkDist / WALK_SPEED_PER_SEC + option.transfers * TRANSFER_PENALTY_SEC;
		return option;
	}

	/**
	 * 乘车段的行驶方向终点站：线路站序是单向有序的，比较上车站与下车站的位置即可
	 * 判断列车开往哪一端；站序非线性（环线/支线）时返回 null。
	 */
	private static Long terminalStation(MapModel.Route route, List<Long> stations) {
		if (route == null || stations.size() < 2) {
			return null;
		}
		int from = route.stations.indexOf(stations.get(0));
		int to = route.stations.indexOf(stations.get(stations.size() - 1));
		if (from == -1 || to == -1 || from == to) {
			return null;
		}
		return to > from ? route.stations.get(route.stations.size() - 1) : route.stations.get(0);
	}

	/** 全程约需多少分钟 */
	public static int travelMinutes(MapModel model, MapModel.Route route) {
		if (route == null || route.stations.size() < 2) {
			return 0;
		}
		double dist = 0;
		for (int i = 0; i < route.stations.size() - 1; i++) {
			MapModel.Station a = model.station(route.stations.get(i));
			MapModel.Station b = model.station(route.stations.get(i + 1));
			if (i < route.paths.size() && route.paths.get(i) != null && route.paths.get(i).size() >= 2) {
				List<double[]> seg = route.paths.get(i);
				for (int j = 0; j < seg.size() - 1; j++) {
					dist += Math.hypot(seg.get(j + 1)[0] - seg.get(j)[0], seg.get(j + 1)[1] - seg.get(j)[1]);
				}
			} else if (a != null && b != null) {
				dist += Math.hypot(b.x - a.x, b.z - a.z);
			}
		}
		double seconds = dist / AVG_SPEED_PER_SEC + (route.stations.size() - 1) * DWELL_SEC;
		return (int) Math.round(seconds / 60.0);
	}
}
