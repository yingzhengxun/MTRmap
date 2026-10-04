package com.mtrmap.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.config.MtrMapConfig;
import com.mtrmap.server.MtrNetwork.DepotInfo;
import com.mtrmap.server.MtrNetwork.PlatformInfo;
import com.mtrmap.server.MtrNetwork.RouteInfo;
import com.mtrmap.server.MtrNetwork.StationInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 MTR 的线网数据转成网页地图用的 JSON。
 * 车站位置取该车站内所有平台中点的平均值（与 MTR 官网地图一致）。
 * 线路按平台顺序连接所属车站（相邻同站去重）。
 * 车厂受配置控制是否显示，显示时使用 60% 透明度（由前端处理）。
 *
 * 【版本无关】MTR 3.x / 4.x 的数据模型差异全部在 {@link MtrNetwork} 里消化掉了，
 * 这里只跟 {@link StationInfo} / {@link PlatformInfo} / {@link RouteInfo} / {@link DepotInfo}
 * 这几个 record 打交道，因此本文件一份代码同时服务 1.19.2 / 1.20.1 / 1.21.1。
 */
public class MapDataCollector {

	/**
	 * 在服务器主线程（SERVER_STARTED）捕获 MTR 的数据对象。
	 * 存档不兼容（如 MTR 版本升降级）时，从 HTTP 线程去取会反复尝试读取存档并抛异常，
	 * 导致地图无法加载；这里只在正确的线程捕获一次。
	 */
	public static void captureRailwayData(MinecraftServer server) {
		MtrNetwork.capture(server);
	}

	/** 清空捕获的数据（服务器关闭时调用） */
	public static void clearRailwayData() {
		MtrNetwork.clear();
	}

	/**
	 * 调试：输出各线路分段寻路诊断（排查站台停车轨道缺失问题时使用）。
	 */
	public static JsonObject debug(MinecraftServer server) {
		JsonObject result = new JsonObject();
		if (!MtrNetwork.isAvailable()) {
			result.addProperty("error", "MTR 数据尚未就绪");
			return result;
		}
		try {
			Map<BlockPos, Map<BlockPos, MtrRailGeometry>> railsMap = MtrNetwork.rails();
			List<PlatformInfo> platforms = MtrNetwork.platforms();
			List<RouteInfo> routes = MtrNetwork.routes();

			result.addProperty("rails", railsMap == null ? 0 : railsMap.size());
			result.addProperty("stations", MtrNetwork.stations().size());
			result.addProperty("platforms", platforms.size());
			result.addProperty("routes", routes.size());

			Map<Long, PlatformInfo> platformById = new HashMap<>();
			for (PlatformInfo platform : platforms) {
				platformById.put(platform.id(), platform);
			}
			Map<Long, Long> platformToStation = MtrNetwork.platformToStation();

			JsonArray routesArr = new JsonArray();
			for (RouteInfo route : routes) {
				JsonObject rObj = new JsonObject();
				rObj.addProperty("name", route.name());
				List<PlatformInfo> platSeq = new ArrayList<>();
				long lastStationId = -1;
				for (Long platformId : route.platformIds()) {
					Long stationId = platformToStation.get(platformId);
					PlatformInfo platform = platformById.get(platformId);
					if (stationId != null && platform != null && stationId != lastStationId) {
						platSeq.add(platform);
						lastStationId = stationId;
					}
				}
				rObj.addProperty("platforms", platSeq.size());
				JsonArray segs = new JsonArray();
				if (railsMap != null) {
					for (int i = 1; i < platSeq.size(); i++) {
						segs.add(RailPathFinder.debugPath(platSeq.get(i - 1), platSeq.get(i), railsMap));
					}
				}
				rObj.add("segments", segs);
				routesArr.add(rObj);
			}
			result.add("routes", routesArr);
		} catch (Exception e) {
			result.addProperty("error", e.toString());
		}
		return result;
	}

	public static JsonObject collect(MinecraftServer server) {
		return collect(server, true);
	}

	/**
	 * @param includeTrains 是否附带列车数据。Xaero 叠加层只需要线网几何
	 *                      （车站/线路/车厂），走 /api/overlay 时传 false，
	 *                      可以省掉列车采集的开销。
	 */
	public static JsonObject collect(MinecraftServer server, boolean includeTrains) {
		JsonObject result = new JsonObject();
		if (!MtrNetwork.isAvailable()) {
			return result;
		}

		List<StationInfo> stations = MtrNetwork.stations();
		List<PlatformInfo> platforms = MtrNetwork.platforms();
		List<RouteInfo> routes = MtrNetwork.routes();
		List<DepotInfo> depots = MtrNetwork.depots();

		// 平台ID -> 所属车站ID（由数据层按 MTR 自己的范围判断算好）
		Map<Long, Long> platformToStation = MtrNetwork.platformToStation();
		// 车站ID -> 平台列表
		Map<Long, List<PlatformInfo>> stationToPlatforms = new HashMap<>();
		// 平台ID -> 平台（用于真实线位与列车）
		Map<Long, PlatformInfo> platformById = new HashMap<>();

		for (PlatformInfo platform : platforms) {
			Long stationId = platformToStation.get(platform.id());
			if (stationId != null) {
				stationToPlatforms.computeIfAbsent(stationId, k -> new ArrayList<>()).add(platform);
			}
			platformById.put(platform.id(), platform);
		}

		// 轨道网络快照（数据层已复制并重试；失败则回退为直线线位）
		Map<BlockPos, Map<BlockPos, MtrRailGeometry>> railsMap = MtrNetwork.rails();

		// 线路按主名分组（"|" 前名称相同的上行/下行合并为一条单线）
		Map<String, List<RouteInfo>> routeGroups = new LinkedHashMap<>();
		// 线路ID -> 线路（车厂的 routeIds 要用它反查第一条线路）
		Map<Long, RouteInfo> routeById = new HashMap<>();
		// 车站在线路中的第一个停靠平台中心（保证站点点位正好落在线路折线上）
		Map<Long, BlockPos> stationLineMid = new HashMap<>();
		for (RouteInfo route : routes) {
			routeById.put(route.id(), route);
			routeGroups.computeIfAbsent(stripRouteComment(route.name()), k -> new ArrayList<>()).add(route);
			long lastStationId = -1;
			for (Long platformId : route.platformIds()) {
				Long stationId = platformToStation.get(platformId);
				PlatformInfo platform = platformById.get(platformId);
				if (stationId != null && platform != null && stationId != lastStationId) {
					stationLineMid.putIfAbsent(stationId, platform.mid());
					lastStationId = stationId;
				}
			}
		}

		// 车站数据
		// 车站ID -> 显示坐标（合并上行/下行线路时回退直线使用）
		Map<Long, double[]> stationCenter = new HashMap<>();
		// 车站ID -> 该站全部站台中心的包围盒（minX, minZ, maxX, maxZ）。
		// 换乘站的跑道形标记要盖住经过该站的所有线路，长度与朝向都由这个包围盒决定：
		// 站台沿南北排开时包围盒变高，标记就竖过来。
		Map<Long, double[]> stationBounds = new HashMap<>();
		JsonArray stationsArray = new JsonArray();
		for (StationInfo station : stations) {
			JsonObject obj = new JsonObject();
			obj.addProperty("id", station.id());
			obj.addProperty("name", station.name() == null ? "" : station.name());
			obj.addProperty("color", station.color());

			double cx, cz;
			BlockPos lineMid = stationLineMid.get(station.id());
			List<PlatformInfo> stationPlatforms = stationToPlatforms.get(station.id());
			if (lineMid != null) {
				// 优先用线路停靠平台中心，保证点位落在线上
				cx = lineMid.getX();
				cz = lineMid.getZ();
			} else if (stationPlatforms != null && !stationPlatforms.isEmpty()) {
				double sumX = 0, sumZ = 0;
				for (PlatformInfo p : stationPlatforms) {
					BlockPos mid = p.mid();
					if (mid != null) {
						sumX += mid.getX();
						sumZ += mid.getZ();
					}
				}
				cx = sumX / stationPlatforms.size();
				cz = sumZ / stationPlatforms.size();
			} else {
				BlockPos center = station.center();
				if (center == null) {
					MtrMapCommon.LOGGER.warn("车站 [{}] 没有 center 坐标，已跳过", station.name());
					continue;
				}
				cx = center.getX();
				cz = center.getZ();
			}
			obj.addProperty("x", cx);
			obj.addProperty("z", cz);
			stationCenter.put(station.id(), new double[]{cx, cz});
			stationsArray.add(obj);

			if (stationPlatforms != null && !stationPlatforms.isEmpty()) {
				double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
				double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
				for (PlatformInfo p : stationPlatforms) {
					BlockPos mid = p.mid();
					if (mid == null) {
						continue;
					}
					minX = Math.min(minX, mid.getX());
					maxX = Math.max(maxX, mid.getX());
					minZ = Math.min(minZ, mid.getZ());
					maxZ = Math.max(maxZ, mid.getZ());
				}
				if (minX <= maxX) {
					stationBounds.put(station.id(), new double[]{minX, minZ, maxX, maxZ});
				}
			}
		}
		result.add("stations", stationsArray);

		// 线路数据（routeGroups 已在上面按主名分组）
		// MTR 中上行/下行是两条"名称 | 注释"记录（"|" 后为注释），
		// "|" 前名称相同的线路合并为一条单线显示，避免地图上出现两条平行线。

		// 第一遍：逐条线路计算车站序列与相邻段折线（fromStationId -> toStationId -> 折线）
		Map<Long, List<Long>> stationIdsByRoute = new HashMap<>();
		Map<Long, Map<Long, Map<Long, JsonArray>>> segmentsByRoute = new HashMap<>();
		for (RouteInfo route : routes) {
			List<Long> ids = new ArrayList<>();
			List<PlatformInfo> platSeq = new ArrayList<>();
			long lastStationId = -1;
			for (Long platformId : route.platformIds()) {
				Long stationId = platformToStation.get(platformId);
				PlatformInfo platform = platformById.get(platformId);
				if (stationId != null && platform != null && stationId != lastStationId) {
					ids.add(stationId);
					platSeq.add(platform);
					lastStationId = stationId;
				}
			}
			stationIdsByRoute.put(route.id(), ids);

			// 真实停靠线位：相邻站台间沿轨道网络寻路。
			// 轨道只连接到站台角点，折线首尾补上站台中心（列车实际停靠处），
			// 否则线位会停在站台边缘、显示不出"站台停车轨道"。
			Map<Long, Map<Long, JsonArray>> segs = new HashMap<>();
			for (int i = 1; i < platSeq.size(); i++) {
				JsonArray seg = new JsonArray();
				// 起点：上一站台的中心
				addPoint(seg, platSeq.get(i - 1).mid());
				List<double[]> polyline = railsMap == null ? null
						: RailPathFinder.findPath(platSeq.get(i - 1), platSeq.get(i), railsMap);
				if (polyline != null) {
					for (double[] pt : polyline) {
						JsonObject point = new JsonObject();
						point.addProperty("x", pt[0]);
						point.addProperty("z", pt[1]);
						seg.add(point);
					}
				}
				// 终点：下一站台的中心
				addPoint(seg, platSeq.get(i).mid());
				segs.computeIfAbsent(ids.get(i - 1), k -> new HashMap<>()).put(ids.get(i), seg);
			}
			segmentsByRoute.put(route.id(), segs);
		}

		// 线路 -> 发车间隔（分钟）：由服务该线路的车厂时刻表推算，取最频繁的那个车厂。
		// 网页的线路信息侧边栏用它显示「约几分钟一班」。
		Map<Long, Integer> routeHeadway = new HashMap<>();
		for (DepotInfo depot : depots) {
			int headway = depot.headwayMinutes();
			if (headway <= 0) {
				continue;
			}
			for (long routeId : depot.routeIds()) {
				Integer prev = routeHeadway.get(routeId);
				if (prev == null || headway < prev) {
					routeHeadway.put(routeId, headway);
				}
			}
		}

		// 第二遍：按组合并输出为单线
		JsonArray routesArray = new JsonArray();
		for (Map.Entry<String, List<RouteInfo>> group : routeGroups.entrySet()) {
			List<RouteInfo> groupRoutes = group.getValue();
			RouteInfo first = groupRoutes.get(0);

			// 合并车站序列（去重保序）
			List<Long> mergedIds = new ArrayList<>();
			for (RouteInfo r : groupRoutes) {
				List<Long> ids = stationIdsByRoute.get(r.id());
				if (ids == null) {
					continue;
				}
				for (Long sid : ids) {
					if (!mergedIds.contains(sid)) {
						mergedIds.add(sid);
					}
				}
			}

			JsonObject obj = new JsonObject();
			obj.addProperty("id", first.id());
			obj.addProperty("name", group.getKey());
			obj.addProperty("color", first.color());

			// 发车间隔（分钟），0 表示没配车厂时刻表、无法推算
			int headway = 0;
			for (RouteInfo r : groupRoutes) {
				Integer h = routeHeadway.get(r.id());
				if (h != null && (headway == 0 || h < headway)) {
					headway = h;
				}
			}
			obj.addProperty("headway", headway);

			JsonArray stationIds = new JsonArray();
			for (Long sid : mergedIds) {
				stationIds.add(sid);
			}
			obj.add("stations", stationIds);

			// 合并折线段：优先取组内任意线路的正向段，其次取反向段（倒序），否则回退两站中心直线
			JsonArray pathsArray = new JsonArray();
			for (int i = 1; i < mergedIds.size(); i++) {
				long from = mergedIds.get(i - 1);
				long to = mergedIds.get(i);
				JsonArray seg = null;
				for (RouteInfo r : groupRoutes) {
					Map<Long, Map<Long, JsonArray>> segs = segmentsByRoute.get(r.id());
					if (segs == null) {
						continue;
					}
					JsonArray direct = segs.get(from) == null ? null : segs.get(from).get(to);
					if (direct != null) {
						seg = direct;
						break;
					}
					JsonArray reversed = segs.get(to) == null ? null : segs.get(to).get(from);
					if (reversed != null) {
						seg = reversedSegment(reversed);
						break;
					}
				}
				if (seg == null) {
					double[] a = stationCenter.get(from);
					double[] b = stationCenter.get(to);
					if (a != null && b != null) {
						seg = new JsonArray();
						JsonObject pa = new JsonObject();
						pa.addProperty("x", a[0]);
						pa.addProperty("z", a[1]);
						seg.add(pa);
						JsonObject pb = new JsonObject();
						pb.addProperty("x", b[0]);
						pb.addProperty("z", b[1]);
						seg.add(pb);
					}
				}
				if (seg != null) {
					pathsArray.add(seg);
				}
			}
			obj.add("paths", pathsArray);
			routesArray.add(obj);
		}
		result.add("routes", routesArray);

		// 统计每个车站被多少条线（合并后的单线）经过：≥2 条即为换乘站。
		// 网页地图与 Xaero 叠加层都据此把普通圆点改画成跑道形标记。
		Map<Long, Integer> stationLineCount = new HashMap<>();
		for (JsonElement element : routesArray) {
			JsonArray ids = element.getAsJsonObject().getAsJsonArray("stations");
			for (JsonElement sid : ids) {
				stationLineCount.merge(sid.getAsLong(), 1, Integer::sum);
			}
		}
		for (JsonElement element : stationsArray) {
			JsonObject obj = element.getAsJsonObject();
			long sid = obj.get("id").getAsLong();
			int lineCount = stationLineCount.getOrDefault(sid, 0);
			obj.addProperty("lines", lineCount);
			// 换乘站再带上站台包围盒，供前端画「盖住所有线路」的跑道形标记
			double[] bounds = stationBounds.get(sid);
			if (lineCount >= 2 && bounds != null) {
				obj.addProperty("bx1", bounds[0]);
				obj.addProperty("bz1", bounds[1]);
				obj.addProperty("bx2", bounds[2]);
				obj.addProperty("bz2", bounds[3]);
			}
		}

		// 车厂数据：始终下发，由网页地图的按钮 / Xaero 叠加层的开关各自决定是否显示。
		// showDepots 字段仅作为前端的初始默认值。
		JsonArray depotsArray = new JsonArray();
		for (DepotInfo depot : depots) {
			JsonObject obj = new JsonObject();
			obj.addProperty("id", depot.id());
			obj.addProperty("name", depot.name() == null ? "" : depot.name());
			obj.addProperty("color", depot.color());
			obj.addProperty("x1", depot.minX());
			obj.addProperty("z1", depot.minZ());
			obj.addProperty("x2", depot.maxX());
			obj.addProperty("z2", depot.maxZ());
			obj.addProperty("centerX", (depot.minX() + depot.maxX()) / 2.0);
			obj.addProperty("centerZ", (depot.minZ() + depot.maxZ()) / 2.0);

			// 车厂关联的第一个车站与第一个站台（取第一条线路的第一个平台）
			long firstStationId = -1;
			PlatformInfo firstPlatform = null;
			for (long routeId : depot.routeIds()) {
				RouteInfo route = routeById.get(routeId);
				if (route != null && !route.platformIds().isEmpty()) {
					long firstPlatformId = route.platformIds().get(0);
					Long stationId = platformToStation.get(firstPlatformId);
					if (stationId != null) {
						firstStationId = stationId;
						firstPlatform = platformById.get(firstPlatformId);
						break;
					}
				}
			}
			obj.addProperty("firstStationId", firstStationId);
			// 连接线站台侧端点：第一个站台的中心（列车停靠位置，与线路起点重合）
			if (firstPlatform != null) {
				BlockPos mid = firstPlatform.mid();
				if (mid != null) {
					obj.addProperty("firstPlatformX", (double) mid.getX());
					obj.addProperty("firstPlatformZ", (double) mid.getZ());
				}
				// 真实路径连接线：从站台中心沿轨道寻路到车厂矩形，末端截断到车厂边界
				if (railsMap != null) {
					List<double[]> link = RailPathFinder.findDepotLink(firstPlatform,
							depot.minX(), depot.minZ(), depot.maxX(), depot.maxZ(), railsMap);
					if (link != null && !link.isEmpty()) {
						JsonArray path = new JsonArray();
						if (mid != null) {
							addPoint(path, mid);
						}
						for (double[] pt : link) {
							JsonObject point = new JsonObject();
							point.addProperty("x", pt[0]);
							point.addProperty("z", pt[1]);
							path.add(point);
						}
						obj.add("path", path);
					}
				}
			}
			depotsArray.add(obj);
		}
		result.add("depots", depotsArray);
		result.addProperty("showDepots", MtrMapConfig.isShowDepots());

		// 列车数据
		if (includeTrains) {
			JsonArray trainsArray = new JsonArray();
			TrainCollector.collect(server, trainsArray);
			result.add("trains", trainsArray);
		}

		return result;
	}

	private static void addPoint(JsonArray seg, BlockPos pos) {
		if (pos != null) {
			JsonObject point = new JsonObject();
			point.addProperty("x", (double) pos.getX());
			point.addProperty("z", (double) pos.getZ());
			seg.add(point);
		}
	}

	/** 去掉线路名中 "|" 后的注释部分，得到线路主名称 */
	private static String stripRouteComment(String name) {
		if (name == null) {
			return "";
		}
		int idx = name.indexOf("|");
		return (idx >= 0 ? name.substring(0, idx) : name).trim();
	}

	/** 倒序折线点数组（用于把下行段作为上行段复用） */
	private static JsonArray reversedSegment(JsonArray seg) {
		JsonArray reversed = new JsonArray();
		for (int i = seg.size() - 1; i >= 0; i--) {
			reversed.add(seg.get(i));
		}
		return reversed;
	}
}
