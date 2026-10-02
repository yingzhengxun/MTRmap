package com.mtrmap.server;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.server.MtrNetwork.PlatformInfo;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在 MTR 轨道网络中寻找真实轨道路径。
 *
 * 使用 {@link MtrNetwork#rails()} 提供的轨道邻接表做 Dijkstra（按轨长取最短），
 * 找到路径后沿每条轨道采样坐标点，得到折线（近似真实线位）。
 * 结果缓存 30 秒，减少重复计算。
 *
 * 支持两类查询：
 * 1. 站台 -> 站台（线路真实停靠线位）
 * 2. 站台 -> 车厂矩形（车厂连接线，末端截断到车厂边界）
 *
 * 站台与轨道端点的匹配采用包围盒 + 容差判断（而非 MTR 的精确角块匹配），
 * 确保连接到站台任意块的停靠轨道也能被包含进路径。
 *
 * 轨道本身用 {@link MtrRailGeometry} 抽象，MTR 3.x / 4.x 的 Rail 差异由数据层消化。
 */
public class RailPathFinder {

	private static final long TTL_MS = 30_000;
	private static final double SAMPLE_INTERVAL = 3.0; // 每 3 格采样一个点
	private static final int PLATFORM_MATCH_RADIUS = 4; // 站台匹配容差（格）

	private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

	/** 寻路结果缓存条目（写成普通类而非 record：1.16.5 的编译目标是 Java 8） */
	private static final class CacheEntry {
		private final long time;
		private final List<double[]> polyline;

		private CacheEntry(long time, List<double[]> polyline) {
			this.time = time;
			this.polyline = polyline;
		}
	}

	/** 返回 a->b 的真实轨道折线（[x,z] 数组序列），找不到路径时返回 null */
	public static List<double[]> findPath(PlatformInfo a, PlatformInfo b,
			Map<BlockPos, Map<BlockPos, MtrRailGeometry>> railsMap) {
		String key = a.id() + ":" + b.id();
		CacheEntry cached = CACHE.get(key);
		if (cached != null && System.currentTimeMillis() - cached.time < TTL_MS) {
			return cached.polyline;
		}
		List<double[]> polyline = compute(a, b, railsMap);
		CACHE.put(key, new CacheEntry(System.currentTimeMillis(), polyline));
		return polyline;
	}

	/**
	 * 从平台 a 沿真实轨道寻路，直到进入车厂矩形范围。
	 * 返回折线（不含平台中心首点，末端截断到车厂矩形边界）；找不到路径返回 null。
	 */
	public static List<double[]> findDepotLink(PlatformInfo a, int minX, int minZ, int maxX, int maxZ,
			Map<BlockPos, Map<BlockPos, MtrRailGeometry>> railsMap) {
		String key = "depot:" + a.id() + ":" + minX + "," + minZ + "," + maxX + "," + maxZ;
		CacheEntry cached = CACHE.get(key);
		if (cached != null && System.currentTimeMillis() - cached.time < TTL_MS) {
			return cached.polyline;
		}
		List<double[]> polyline = computeToRect(a, minX, minZ, maxX, maxZ, railsMap);
		CACHE.put(key, new CacheEntry(System.currentTimeMillis(), polyline));
		return polyline;
	}

	public static void clear() {
		CACHE.clear();
	}

	/** 双向邻接图 + 全部节点 */
	private static final class Graph {
		private final Map<BlockPos, Map<BlockPos, MtrRailGeometry>> adj;
		private final Set<BlockPos> nodes;

		private Graph(Map<BlockPos, Map<BlockPos, MtrRailGeometry>> adj, Set<BlockPos> nodes) {
			this.adj = adj;
			this.nodes = nodes;
		}

		private Map<BlockPos, Map<BlockPos, MtrRailGeometry>> adj() {
			return adj;
		}

		private Set<BlockPos> nodes() {
			return nodes;
		}
	}

	private static Graph buildGraph(Map<BlockPos, Map<BlockPos, MtrRailGeometry>> railsMap) {
		// MTR 的 rails map 每条轨道只按"规范方向"存一次（单向），但轨道本身双向可通行。
		// 构造双向邻接图，否则从站台位置出发可能没有出边，导致停靠轨道被排除。
		Map<BlockPos, Map<BlockPos, MtrRailGeometry>> graph = new HashMap<>();
		Set<BlockPos> nodes = new HashSet<>();
		for (Map.Entry<BlockPos, Map<BlockPos, MtrRailGeometry>> entry : railsMap.entrySet()) {
			BlockPos from = entry.getKey();
			nodes.add(from);
			for (Map.Entry<BlockPos, MtrRailGeometry> neighbor : entry.getValue().entrySet()) {
				BlockPos to = neighbor.getKey();
				MtrRailGeometry rail = neighbor.getValue();
				nodes.add(to);
				graph.computeIfAbsent(from, k -> new HashMap<>()).put(to, rail);
				graph.computeIfAbsent(to, k -> new HashMap<>()).put(from, rail);
			}
		}
		return new Graph(graph, nodes);
	}

	private static List<double[]> compute(PlatformInfo a, PlatformInfo b,
			Map<BlockPos, Map<BlockPos, MtrRailGeometry>> railsMap) {
		try {
			Graph graph = buildGraph(railsMap);
			if (graph.nodes().isEmpty()) {
				return null;
			}
			PathResult result = dijkstra(a, b, graph);
			if (result == null || result.chain == null) {
				return null;
			}
			return sampleChain(result.chain, graph);
		} catch (Exception e) {
			MtrMapCommon.LOGGER.debug("轨道寻路失败: {} -> {}", a.id(), b.id(), e);
			return null;
		}
	}

	/** 从平台 a 寻路到车厂矩形内的任一轨道节点，折线末端截断到矩形边界 */
	private static List<double[]> computeToRect(PlatformInfo a, int minX, int minZ, int maxX, int maxZ,
			Map<BlockPos, Map<BlockPos, MtrRailGeometry>> railsMap) {
		try {
			Graph graph = buildGraph(railsMap);
			if (graph.nodes().isEmpty()) {
				return null;
			}
			Set<BlockPos> start = matchPlatformEndpoints(a, graph.nodes(), 0);
			if (start.isEmpty()) {
				start = matchPlatformEndpoints(a, graph.nodes(), PLATFORM_MATCH_RADIUS);
			}
			if (start.isEmpty()) {
				return null;
			}
			Set<BlockPos> end = new HashSet<>();
			for (BlockPos pos : graph.nodes()) {
				if (pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
					end.add(pos);
				}
			}
			if (end.isEmpty()) {
				int pad = PLATFORM_MATCH_RADIUS;
				for (BlockPos pos : graph.nodes()) {
					if (pos.getX() >= minX - pad && pos.getX() <= maxX + pad
							&& pos.getZ() >= minZ - pad && pos.getZ() <= maxZ + pad) {
						end.add(pos);
					}
				}
			}
			if (end.isEmpty()) {
				return null;
			}
			PathResult result = runDijkstra(start, end, graph);
			if (result == null) {
				return null;
			}
			List<double[]> polyline = sampleChain(result.chain, graph);
			if (polyline == null || polyline.isEmpty()) {
				return null;
			}
			// 末端截断到车厂矩形边界：最后一个点 -> 车厂中心 的连线与矩形边界的交点
			double cx = (minX + maxX) / 2.0;
			double cz = (minZ + maxZ) / 2.0;
			double[] last = polyline.get(polyline.size() - 1);
			double[] edge = intersectRect(last[0], last[1], cx, cz, minX, minZ, maxX, maxZ);
			if (edge != null) {
				polyline.set(polyline.size() - 1, edge);
			}
			return polyline;
		} catch (Exception e) {
			MtrMapCommon.LOGGER.debug("车厂连接线寻路失败: platform {} -> depot", a.id(), e);
			return null;
		}
	}

	/** 沿节点链采样每段轨道，拼成折线 */
	private static List<double[]> sampleChain(List<BlockPos> chain, Graph graph) {
		List<double[]> polyline = new ArrayList<>();
		for (int i = 0; i < chain.size() - 1; i++) {
			BlockPos from = chain.get(i);
			BlockPos to = chain.get(i + 1);
			Map<BlockPos, MtrRailGeometry> neighbors = graph.adj().get(from);
			if (neighbors == null) {
				return null;
			}
			MtrRailGeometry rail = neighbors.get(to);
			if (rail == null) {
				return null;
			}
			List<double[]> points = sampleRail(from, to, rail);
			if (points == null) {
				return null;
			}
			// 跳过上一段最后一个点（两段轨道在节点处重合）
			if (!polyline.isEmpty() && !points.isEmpty()) {
				points.remove(0);
			}
			polyline.addAll(points);
		}
		return polyline.isEmpty() ? null : polyline;
	}

	/** Dijkstra 结果：路径节点链 + 匹配到的起点/终点集 */
	private static final class PathResult {
		private final List<BlockPos> chain;
		private final Set<BlockPos> start;
		private final Set<BlockPos> end;

		private PathResult(List<BlockPos> chain, Set<BlockPos> start, Set<BlockPos> end) {
			this.chain = chain;
			this.start = start;
			this.end = end;
		}
	}

	/**
	 * 平台 a -> 平台 b 的最短路径。
	 * 优先用站台 AABB 精确匹配（命中站台自身的轨道端点，包含停靠轨道），失败再放宽容差。
	 */
	private static PathResult dijkstra(PlatformInfo a, PlatformInfo b, Graph graph) {
		Set<BlockPos> start = matchPlatformEndpoints(a, graph.nodes(), 0);
		Set<BlockPos> end = matchPlatformEndpoints(b, graph.nodes(), 0);
		if (start.isEmpty() || end.isEmpty()) {
			start = matchPlatformEndpoints(a, graph.nodes(), PLATFORM_MATCH_RADIUS);
			end = matchPlatformEndpoints(b, graph.nodes(), PLATFORM_MATCH_RADIUS);
		}
		return runDijkstra(start, end, graph);
	}

	/** 在双向图上从 start 集合中的任意点走到 end 集合中任意点的最短路径 */
	private static PathResult runDijkstra(Set<BlockPos> start, Set<BlockPos> end, Graph graph) {
		if (start.isEmpty() || end.isEmpty()) {
			return null;
		}
		Map<BlockPos, Double> dist = new HashMap<>();
		Map<BlockPos, BlockPos> prev = new HashMap<>();
		PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingDouble(dist::get));
		for (BlockPos s : start) {
			dist.put(s, 0.0);
			queue.add(s);
		}

		BlockPos target = null;
		while (!queue.isEmpty()) {
			BlockPos u = queue.poll();
			if (end.contains(u)) {
				target = u;
				break;
			}
			Map<BlockPos, MtrRailGeometry> neighbors = graph.adj().get(u);
			if (neighbors == null) {
				continue;
			}
			double base = dist.get(u);
			for (Map.Entry<BlockPos, MtrRailGeometry> e : neighbors.entrySet()) {
				BlockPos v = e.getKey();
				MtrRailGeometry rail = e.getValue();
				double newDist = base + rail.length();
				if (newDist < dist.getOrDefault(v, Double.MAX_VALUE)) {
					dist.put(v, newDist);
					prev.put(v, u);
					queue.add(v);
				}
			}
		}
		if (target == null) {
			return null;
		}

		List<BlockPos> chain = new ArrayList<>();
		BlockPos cur = target;
		while (cur != null) {
			chain.add(cur);
			cur = prev.get(cur);
		}
		Collections.reverse(chain);
		return new PathResult(chain, start, end);
	}

	/**
	 * 调试：输出平台 a->b 寻路的诊断信息（起点/终点匹配、路径段数、折线点数）。
	 * 仅在排查线路线位问题时使用。
	 */
	public static com.google.gson.JsonObject debugPath(PlatformInfo a, PlatformInfo b,
			Map<BlockPos, Map<BlockPos, MtrRailGeometry>> railsMap) {
		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
		obj.addProperty("a", summarize(a));
		obj.addProperty("b", summarize(b));
		try {
			Graph graph = buildGraph(railsMap);
			obj.addProperty("edges", railsMap.size());
			obj.addProperty("nodes", graph.nodes().size());

			PathResult result = dijkstra(a, b, graph);
			if (result == null) {
				obj.addProperty("path", "NOT_FOUND");
				obj.addProperty("strictStart", matchPlatformEndpoints(a, graph.nodes(), 0).size());
				obj.addProperty("strictEnd", matchPlatformEndpoints(b, graph.nodes(), 0).size());
				Set<BlockPos> radiusStart = matchPlatformEndpoints(a, graph.nodes(), PLATFORM_MATCH_RADIUS);
				Set<BlockPos> radiusEnd = matchPlatformEndpoints(b, graph.nodes(), PLATFORM_MATCH_RADIUS);
				obj.addProperty("radiusStart", radiusStart.size());
				obj.addProperty("radiusEnd", radiusEnd.size());
				obj.add("start", positionsJson(radiusStart));
				obj.add("end", positionsJson(radiusEnd));
				return obj;
			}
			obj.add("start", positionsJson(result.start));
			obj.add("end", positionsJson(result.end));
			obj.addProperty("chainRails", result.chain.size() - 1);
			obj.addProperty("chainStart", result.chain.get(0).getX() + "," + result.chain.get(0).getZ());
			obj.addProperty("chainEnd", result.chain.get(result.chain.size() - 1).getX() + "," + result.chain.get(result.chain.size() - 1).getZ());
		} catch (Exception e) {
			obj.addProperty("error", e.toString());
		}
		return obj;
	}

	private static com.google.gson.JsonArray positionsJson(Set<BlockPos> positions) {
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (BlockPos p : positions) {
			arr.add(p.getX() + "," + p.getZ());
		}
		return arr;
	}

	private static String summarize(PlatformInfo p) {
		BlockPos mid = p.mid();
		StringBuilder sb = new StringBuilder("id=").append(p.id());
		sb.append(" mid=").append(mid == null ? "null" : mid.getX() + "," + mid.getZ());
		List<BlockPos> corners = p.corners();
		if (corners != null && !corners.isEmpty()) {
			sb.append(" corners=");
			for (BlockPos c : corners) {
				sb.append("[").append(c.getX()).append(",").append(c.getZ()).append("]");
			}
		}
		return sb.toString();
	}

	/** 匹配轨道端点中落在站台包围盒（含容差）内的位置 */
	private static Set<BlockPos> matchPlatformEndpoints(PlatformInfo platform, Set<BlockPos> nodes, int radius) {
		Set<BlockPos> result = new HashSet<>();
		for (BlockPos pos : nodes) {
			if (isNearPlatform(platform, pos, radius)) {
				result.add(pos);
			}
		}
		return result;
	}

	/** 判断 pos 是否位于站台包围盒内（含容差，不限制 Y） */
	private static boolean isNearPlatform(PlatformInfo platform, BlockPos pos, int radius) {
		List<BlockPos> corners = platform.corners();
		if (corners == null || corners.size() < 2) {
			// 拿不到站台端点时兜底：按站台中点距离判断
			BlockPos mid = platform.mid();
			if (mid == null) {
				return false;
			}
			double dx = pos.getX() - mid.getX();
			double dz = pos.getZ() - mid.getZ();
			int r = radius > 0 ? radius : PLATFORM_MATCH_RADIUS;
			return dx * dx + dz * dz <= r * r;
		}
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos corner : corners) {
			minX = Math.min(minX, corner.getX());
			maxX = Math.max(maxX, corner.getX());
			minZ = Math.min(minZ, corner.getZ());
			maxZ = Math.max(maxZ, corner.getZ());
		}
		return pos.getX() >= minX - radius && pos.getX() <= maxX + radius
				&& pos.getZ() >= minZ - radius && pos.getZ() <= maxZ + radius;
	}

	/** 采样一条轨道的折线点（[x,z]），方向与 from->to 一致 */
	private static List<double[]> sampleRail(BlockPos from, BlockPos to, MtrRailGeometry rail) {
		try {
			double length = rail.length();
			int steps = Math.max(1, (int) Math.ceil(length / SAMPLE_INTERVAL));
			double[] start = rail.position(0);
			// 判断轨道方向：起点更靠近 from 还是 to
			boolean reversed = distanceSq(start, from) > distanceSq(start, to);
			List<double[]> points = new ArrayList<>(steps + 1);
			for (int k = 0; k <= steps; k++) {
				double progress = length * k / steps;
				if (reversed) {
					progress = length - progress;
				}
				points.add(rail.position(progress));
			}
			return points;
		} catch (Exception e) {
			return null;
		}
	}

	/** 线段 (x0,z0)->(x1,z1) 与矩形边界交点中离起点最近的一个；无交点返回 null */
	private static double[] intersectRect(double x0, double z0, double x1, double z1,
			int minX, int minZ, int maxX, int maxZ) {
		double dx = x1 - x0;
		double dz = z1 - z0;
		double tBest = Double.MAX_VALUE;
		double bestX = 0, bestZ = 0;
		if (dx != 0) {
			for (double t : new double[]{(minX - x0) / dx, (maxX - x0) / dx}) {
				if (t > 0 && t <= 1) {
					double z = z0 + dz * t;
					if (z >= minZ && z <= maxZ && t < tBest) {
						tBest = t;
						bestX = x0 + dx * t;
						bestZ = z;
					}
				}
			}
		}
		if (dz != 0) {
			for (double t : new double[]{(minZ - z0) / dz, (maxZ - z0) / dz}) {
				if (t > 0 && t <= 1) {
					double x = x0 + dx * t;
					if (x >= minX && x <= maxX && t < tBest) {
						tBest = t;
						bestX = x;
						bestZ = z0 + dz * t;
					}
				}
			}
		}
		return tBest == Double.MAX_VALUE ? null : new double[]{bestX, bestZ};
	}

	private static double distanceSq(double[] pos, BlockPos blockPos) {
		double dx = pos[0] - (blockPos.getX() + 0.5);
		double dz = pos[1] - (blockPos.getZ() + 0.5);
		return dx * dx + dz * dz;
	}
}
