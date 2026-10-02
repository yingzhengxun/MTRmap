package com.mtrmap.server;

import com.mtrmap.MtrMapCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//? if >=1.21.1 {
/*import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.SimpleAreaBase;
import org.mtr.core.data.Station;
import org.mtr.core.data.Vehicle;
import org.mtr.core.tool.Vector;
*///?} else {
import mtr.data.Depot;
import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.data.Train;
import mtr.data.TrainServer;
import mtr.path.PathData;
import net.minecraft.world.phys.Vec3;
//?}

/**
 * MTR 数据的版本适配层：把「怎么拿数据」和「数据长什么样」的版本差异全部挡在这里，
 * 上层的 {@link MapDataCollector} / {@link TrainCollector} / {@link RailPathFinder}
 * 只跟下面几个 record 打交道。
 *
 * 【MTR 3.x（1.19.2 / 1.20.1）】
 *   服务端数据挂在世界存档上，`RailwayData.getInstance(world)` 直接可取。
 *   集合是 `data.stations / platforms / routes / depots`，轨道邻接表是 `getRailsMap()`，
 *   坐标用 `BlockPos`，名称与颜色是 public 字段。
 *
 * 【MTR 4.x（1.21.1）】
 *   没有 `RailwayData` 了：数据在 `org.mtr.core.simulation.Simulator` 里，
 *   而 `Simulator` 继承自 `org.mtr.core.data.Data`（集合都是 public 字段）。
 *   Simulator 由 `org.mtr.core.Main` 持有，`Main` 实例又是 MTR 入口类里的私有静态字段，
 *   所以只能用反射拿。坐标改成 `Position`（long 的 x/y/z），轨道邻接表是 `data.positionsToRail`，
 *   名称/颜色改成 getter。
 *
 * 【为什么打包成 record 快照而不是直接暴露 MTR 对象】
 *   两种 MTR 的对象类型毫无关系，如果上层直接引用它们，每个取值点都要写 `//?` 分支。
 *   这里一次性转换完，上层代码就是纯 JSON 组装逻辑，一份代码三端共用。
 *
 * 【注意】`//?` 的 else 分支（MTR 3.x）是"激活版本"（1.20.1）用的，所以写明文；
 *   4.x 分支在源码里必须保持块注释状态，因此**分支内部只能用 // 行注释**，
 *   不能出现 / * * / 这类序列，否则外层块注释会被提前闭合。
 */
public final class MtrNetwork {

	// 下面几个快照类型写成普通 final 类而不是 record：1.16.5 的编译目标是 Java 8，
	// record 需要 Java 16+。字段私有、访问器方法名与 record 的组件名一致，
	// 所以上层（MapDataCollector / RailPathFinder / TrainCollector）的 .id() / .mid() 写法无需改动。

	/** 车站：中心坐标允许为 null（老存档可能没有 corner），由上层决定怎么兜底 */
	public static final class StationInfo {
		private final long id;
		private final String name;
		private final int color;
		private final BlockPos center;

		public StationInfo(long id, String name, int color, BlockPos center) {
			this.id = id;
			this.name = name;
			this.color = color;
			this.center = center;
		}

		public long id() {
			return id;
		}

		public String name() {
			return name;
		}

		public int color() {
			return color;
		}

		public BlockPos center() {
			return center;
		}
	}

	/** 站台：corners 用于「轨道端点是否贴着这个站台」的包围盒判断，可能为 null */
	public static final class PlatformInfo {
		private final long id;
		private final BlockPos mid;
		private final List<BlockPos> corners;

		public PlatformInfo(long id, BlockPos mid, List<BlockPos> corners) {
			this.id = id;
			this.mid = mid;
			this.corners = corners;
		}

		public long id() {
			return id;
		}

		public BlockPos mid() {
			return mid;
		}

		public List<BlockPos> corners() {
			return corners;
		}
	}

	/** 线路：platformIds 是停靠顺序（含上行/下行各自的记录） */
	public static final class RouteInfo {
		private final long id;
		private final String name;
		private final int color;
		private final List<Long> platformIds;

		public RouteInfo(long id, String name, int color, List<Long> platformIds) {
			this.id = id;
			this.name = name;
			this.color = color;
			this.platformIds = platformIds;
		}

		public long id() {
			return id;
		}

		public String name() {
			return name;
		}

		public int color() {
			return color;
		}

		public List<Long> platformIds() {
			return platformIds;
		}
	}

	/** 车厂：坐标已归一化为 min/max，center 一定非 null */
	public static final class DepotInfo {
		private final long id;
		private final String name;
		private final int color;
		private final int minX;
		private final int minZ;
		private final int maxX;
		private final int maxZ;
		private final BlockPos center;
		private final List<Long> routeIds;

		public DepotInfo(long id, String name, int color,
						int minX, int minZ, int maxX, int maxZ, BlockPos center, List<Long> routeIds) {
			this.id = id;
			this.name = name;
			this.color = color;
			this.minX = minX;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxZ = maxZ;
			this.center = center;
			this.routeIds = routeIds;
		}

		public long id() {
			return id;
		}

		public String name() {
			return name;
		}

		public int color() {
			return color;
		}

		public int minX() {
			return minX;
		}

		public int minZ() {
			return minZ;
		}

		public int maxX() {
			return maxX;
		}

		public int maxZ() {
			return maxZ;
		}

		public BlockPos center() {
			return center;
		}

		public List<Long> routeIds() {
			return routeIds;
		}
	}

	/**
	 * 列车：坐标取车头位置，speedKmh 已换算成 km/h，
	 * riding 是在车上的玩家 UUID（由上层负责换成名字）。
	 */
	public static final class TrainInfo {
		private final String id;
		private final double x;
		private final double z;
		private final boolean onRoute;
		private final int cars;
		private final long speedKmh;
		private final long routeId;
		private final String routeName;
		private final int routeColor;
		private final String destination;
		private final int passengers;
		private final List<UUID> riding;

		public TrainInfo(String id, double x, double z, boolean onRoute, int cars, long speedKmh,
						 long routeId, String routeName, int routeColor, String destination,
						 int passengers, List<UUID> riding) {
			this.id = id;
			this.x = x;
			this.z = z;
			this.onRoute = onRoute;
			this.cars = cars;
			this.speedKmh = speedKmh;
			this.routeId = routeId;
			this.routeName = routeName;
			this.routeColor = routeColor;
			this.destination = destination;
			this.passengers = passengers;
			this.riding = riding;
		}

		public String id() {
			return id;
		}

		public double x() {
			return x;
		}

		public double z() {
			return z;
		}

		public boolean onRoute() {
			return onRoute;
		}

		public int cars() {
			return cars;
		}

		public long speedKmh() {
			return speedKmh;
		}

		public long routeId() {
			return routeId;
		}

		public String routeName() {
			return routeName;
		}

		public int routeColor() {
			return routeColor;
		}

		public String destination() {
			return destination;
		}

		public int passengers() {
			return passengers;
		}

		public List<UUID> riding() {
			return riding;
		}
	}

	public static void capture(MinecraftServer server) {
		Impl.capture(server);
	}

	public static void clear() {
		Impl.clear();
	}

	public static boolean isAvailable() {
		return Impl.isAvailable();
	}

	public static List<StationInfo> stations() {
		return Impl.stations();
	}

	/** 站台 id -> 所属车站 id（没匹配到的不在表里） */
	public static Map<Long, Long> platformToStation() {
		return Impl.platformToStation();
	}

	public static List<PlatformInfo> platforms() {
		return Impl.platforms();
	}

	public static List<RouteInfo> routes() {
		return Impl.routes();
	}

	public static List<DepotInfo> depots() {
		return Impl.depots();
	}

	/**
	 * 轨道邻接表快照（已复制成普通 HashMap，避免 HTTP 线程上遍历时被主线程改掉）。
	 * 返回 null 表示这次快照失败，上层应回退成两站之间的直线。
	 */
	public static Map<BlockPos, Map<BlockPos, MtrRailGeometry>> rails() {
		return Impl.rails();
	}

	/** 当前所有列车快照 */
	public static List<TrainInfo> trains() {
		return Impl.trains();
	}

	//? if >=1.21.1 {
	/*// MTR 4.x 实现。
	// 反射点只有两处（MTR 入口类的静态字段 -> Main 的 simulators 列表），
	// 且都用字段名查找 + try/catch 兜底：MTR 换包名或改字段时只会「地图数据为空」，
	// 不会把服务器搞崩。
	private static final class Impl {

		// 可能持有 Main 的入口类（4.0.x 是 org.mtr.mod.Init，4.1.x 改成了 org.mtr.MTR）
		private static final String[] MAIN_HOLDER_CLASSES = {"org.mtr.MTR", "org.mtr.mod.Init"};

		// 服务端数据（实际类型是 Simulator，它继承 Data，这里按 Data 用）
		private static volatile Data cached;

		private static void capture(MinecraftServer server) {
			if (server == null) {
				return;
			}
			try {
				cached = findData(server);
			} catch (Throwable t) {
				MtrMapCommon.LOGGER.error("获取 MTR 服务端数据失败，地图数据将为空", t);
				cached = null;
			}
		}

		private static void clear() {
			cached = null;
		}

		private static boolean isAvailable() {
			return cached != null;
		}

		// 从 MTR 入口类的私有静态字段 main 拿到 org.mtr.core.Main，
		// 再取它的 simulators 列表，按世界 id 找到当前世界对应的那个 Simulator。
		private static Data findData(MinecraftServer server) throws Exception {
			Object main = null;
			for (String className : MAIN_HOLDER_CLASSES) {
				try {
					Field mainField = Class.forName(className).getDeclaredField("main");
					mainField.setAccessible(true);
					main = mainField.get(null);
				} catch (Throwable ignored) {
					// 换下一个候选类名
				}
				if (main != null) {
					break;
				}
			}
			if (main == null) {
				// MTR 还没初始化完（或字段改名），这一轮先不采集
				return null;
			}

			Field simulatorsField = main.getClass().getDeclaredField("simulators");
			simulatorsField.setAccessible(true);
			Object value = simulatorsField.get(main);
			if (!(value instanceof Iterable<?> simulators)) {
				return null;
			}

			String worldId = worldIdOf(server);
			Data first = null;
			for (Object element : simulators) {
				if (!(element instanceof Data data)) {
					continue;
				}
				if (first == null) {
					first = data;
				}
				// Simulator.dimension 是它负责的主维度 id，对上就用它
				if (element instanceof org.mtr.core.simulation.Simulator simulator
						&& worldId != null && worldId.equals(simulator.dimension)) {
					return data;
				}
			}
			return first;
		}

		// 主世界的世界 id；取不到就返回 null，调用方退化成「用第一个 Simulator」
		private static String worldIdOf(MinecraftServer server) {
			try {
				Level world = server.overworld();
				return world == null ? null : world.dimension().location().toString();
			} catch (Throwable t) {
				return null;
			}
		}

		private static List<StationInfo> stations() {
			Data data = cached;
			if (data == null) {
				return List.of();
			}
			List<StationInfo> out = new ArrayList<>();
			for (Station station : new ArrayList<>(data.stations)) {
				out.add(new StationInfo(station.getId(), station.getName(), station.getColor(),
						toBlockPos(station.getCenter())));
			}
			return out;
		}

		private static Map<Long, Long> platformToStation() {
			Data data = cached;
			if (data == null) {
				return Map.of();
			}
			List<Station> stations = new ArrayList<>(data.stations);
			Map<Long, Long> out = new HashMap<>();
			for (Platform platform : new ArrayList<>(data.platforms)) {
				Position mid = platform.getMidPosition();
				if (mid == null) {
					continue;
				}
				for (Station station : stations) {
					if (station.inArea(mid)) {
						out.put(platform.getId(), station.getId());
						break;
					}
				}
			}
			return out;
		}

		private static List<PlatformInfo> platforms() {
			Data data = cached;
			if (data == null) {
				return List.of();
			}
			List<PlatformInfo> out = new ArrayList<>();
			for (Platform platform : new ArrayList<>(data.platforms)) {
				out.add(new PlatformInfo(platform.getId(), toBlockPos(platform.getMidPosition()),
						platformCorners(platform)));
			}
			return out;
		}

		// 站台的包围盒角点。4.x 的站台两个端点字段是 protected，但站台自带一条停靠轨道
		// （SavedRailBase.rail 是 public 字段），直接用这条轨道几何的包围盒更省事，
		// 也不会因为 MTR 内部字段改名而失效。
		private static List<BlockPos> platformCorners(Platform platform) {
			try {
				Rail rail = platform.rail;
				if (rail == null || rail.railMath == null) {
					return null;
				}
				var math = rail.railMath;
				return List.of(new BlockPos((int) math.minX, 0, (int) math.minZ),
						new BlockPos((int) math.maxX, 0, (int) math.maxZ));
			} catch (Throwable t) {
				return null;
			}
		}

		private static List<RouteInfo> routes() {
			Data data = cached;
			if (data == null) {
				return List.of();
			}
			List<RouteInfo> out = new ArrayList<>();
			for (Route route : new ArrayList<>(data.routes)) {
				List<Long> platformIds = new ArrayList<>();
				for (RoutePlatformData routePlatform : route.getRoutePlatforms()) {
					// 4.x 把「站台 id -> Platform」的缓存放在 RoutePlatformData 里，
					// 需要先按 MTR 自己的方式把它填上，否则 getPlatform() 是 null
					routePlatform.writePlatformCache(route, data.platformIdMap);
					Platform platform = routePlatform.getPlatform();
					if (platform != null) {
						platformIds.add(platform.getId());
					}
				}
				out.add(new RouteInfo(route.getId(), route.getName(), route.getColor(), platformIds));
			}
			return out;
		}

		private static List<DepotInfo> depots() {
			Data data = cached;
			if (data == null) {
				return List.of();
			}
			List<DepotInfo> out = new ArrayList<>();
			for (Depot depot : new ArrayList<>(data.depots)) {
				int minX;
				int minZ;
				int maxX;
				int maxZ;
				BlockPos center;
				if (hasValidCorners(depot)) {
					minX = (int) depot.getMinX();
					minZ = (int) depot.getMinZ();
					maxX = (int) depot.getMaxX();
					maxZ = (int) depot.getMaxZ();
					center = new BlockPos((minX + maxX) / 2, 0, (minZ + maxZ) / 2);
				} else {
					Position position = depot.getCenter();
					if (position == null) {
						warnMissingDepot(depot.getId(), depot.getName());
						continue;
					}
					center = toBlockPos(position);
					minX = center.getX() - 3;
					minZ = center.getZ() - 3;
					maxX = center.getX() + 3;
					maxZ = center.getZ() + 3;
				}

				List<Long> routeIds = new ArrayList<>();
				for (long routeId : depot.getRouteIds().toLongArray()) {
					routeIds.add(routeId);
				}
				out.add(new DepotInfo(depot.getId(), depot.getName(), depot.getColor(),
						minX, minZ, maxX, maxZ, center, routeIds));
			}
			return out;
		}

		// 角点无效时 getMinX 之类的返回值不可信
		private static boolean hasValidCorners(Depot depot) {
			try {
				return SimpleAreaBase.validCorners(depot);
			} catch (Throwable t) {
				return false;
			}
		}

		private static Map<BlockPos, Map<BlockPos, MtrRailGeometry>> rails() {
			Data data = cached;
			if (data == null) {
				return null;
			}
			// 轨道表是主线程在改的对象，遍历时可能碰上并发修改；重试几次，失败就让上层走直线
			for (int attempt = 0; attempt < 3; attempt++) {
				try {
					Map<BlockPos, Map<BlockPos, MtrRailGeometry>> out = new HashMap<>();
					for (var entry : data.positionsToRail.entrySet()) {
						Map<BlockPos, MtrRailGeometry> row = new HashMap<>();
						for (var neighbor : entry.getValue().entrySet()) {
							row.put(toBlockPos(neighbor.getKey()), wrap(neighbor.getValue()));
						}
						out.put(toBlockPos(entry.getKey()), row);
					}
					return out;
				} catch (Throwable ignored) {
					// 换个时机再试
				}
			}
			return null;
		}

		private static MtrRailGeometry wrap(Rail rail) {
			return new MtrRailGeometry() {
				@Override
				public double length() {
					return rail.railMath.getLength();
				}

				@Override
				public double[] position(double distance) {
					var vector = rail.railMath.getPosition(distance, false);
					return new double[]{vector.x(), vector.z()};
				}
			};
		}

		// 4.x 的列车挂在线路用的股道（Siding）上，逐条股道遍历即可
		private static List<TrainInfo> trains() {
			Data data = cached;
			if (data == null) {
				return List.of();
			}
			List<TrainInfo> out = new ArrayList<>();
			for (Siding siding : new ArrayList<>(data.sidings)) {
				siding.iterateVehicles(vehicle -> {
					try {
						TrainInfo info = describe(vehicle);
						if (info != null) {
							out.add(info);
						}
					} catch (Throwable t) {
						MtrMapCommon.LOGGER.debug("处理列车数据失败", t);
					}
				});
			}
			return out;
		}

		private static TrainInfo describe(Vehicle vehicle) {
			var extra = vehicle.vehicleExtraData;
			var head = vehicle.getHeadPositionAndTiltAngle();
			if (extra == null || head == null) {
				return null;
			}
			Vector position = head.position();
			int passengers = 0;
			for (var carPassengers : extra.passengers) {
				passengers += carPassengers.size();
			}
			List<UUID> riding = new ArrayList<>();
			extra.iterateRidingEntities(entity -> riding.add(entity.uuid));
			return new TrainInfo(String.valueOf(vehicle.getId()), position.x(), position.z(),
					vehicle.getIsOnRoute(), extra.immutableVehicleCars.size(),
					// MTR 4.x 的速度单位是格/毫秒，换算 km/h 只需 * 3600
					Math.round(readSpeed(vehicle) * 3600),
					extra.getThisRouteId(), extra.getThisRouteName(), extra.getThisRouteColor(),
					extra.getThisRouteDestination(), passengers, riding);
		}

		// 当前速度字段是 protected 且没有 getter，只能反射
		private static final Field speedField = findField(Vehicle.class, "speed");

		private static double readSpeed(Vehicle vehicle) {
			try {
				return speedField == null ? 0 : speedField.getDouble(vehicle);
			} catch (Exception e) {
				return 0;
			}
		}

		private static BlockPos toBlockPos(Position position) {
			return position == null ? null
					: new BlockPos((int) position.getX(), (int) position.getY(), (int) position.getZ());
		}
	}
	*///?} else {
	/**
	 * MTR 3.x 实现：直接用世界存档上的 {@code RailwayData}，坐标为 {@code BlockPos}。
	 */
	private static final class Impl {

		private static volatile RailwayData cached;

		private static void capture(MinecraftServer server) {
			if (server == null) {
				return;
			}
			try {
				Level world = server.overworld();
				cached = world == null ? null : RailwayData.getInstance(world);
			} catch (Exception e) {
				MtrMapCommon.LOGGER.error("服务器线程获取 RailwayData 失败，地图数据将为空", e);
				cached = null;
			}
		}

		private static void clear() {
			cached = null;
		}

		private static boolean isAvailable() {
			return cached != null;
		}

		/** 站台两个端点所在的方块集合（MTR 内部字段，反射读，字段不存在时退化成只用中点判断） */
		private static final Field platformPositionsField = findField(mtr.data.SavedRailBase.class, "positions");

		private static List<StationInfo> stations() {
			RailwayData data = cached;
			if (data == null) {
				return Collections.emptyList();
			}
			List<StationInfo> out = new ArrayList<>();
			for (Station station : new ArrayList<>(data.stations)) {
				out.add(new StationInfo(station.id, station.name, station.color, station.getCenter()));
			}
			return out;
		}

		private static Map<Long, Long> platformToStation() {
			RailwayData data = cached;
			if (data == null) {
				return Collections.emptyMap();
			}
			List<Station> stations = new ArrayList<>(data.stations);
			Map<Long, Long> out = new HashMap<>();
			for (Platform platform : new ArrayList<>(data.platforms)) {
				BlockPos mid = platform.getMidPos();
				if (mid == null) {
					continue;
				}
				for (Station station : stations) {
					if (station.inArea(mid.getX(), mid.getZ())) {
						out.put(platform.id, station.id);
						break;
					}
				}
			}
			return out;
		}

		private static List<PlatformInfo> platforms() {
			RailwayData data = cached;
			if (data == null) {
				return Collections.emptyList();
			}
			List<PlatformInfo> out = new ArrayList<>();
			for (Platform platform : new ArrayList<>(data.platforms)) {
				out.add(new PlatformInfo(platform.id, platform.getMidPos(), corners(platform)));
			}
			return out;
		}

		@SuppressWarnings("unchecked")
		private static List<BlockPos> corners(Platform platform) {
			if (platformPositionsField == null) {
				return null;
			}
			try {
				Set<BlockPos> positions = (Set<BlockPos>) platformPositionsField.get(platform);
				return positions == null ? null : new ArrayList<>(positions);
			} catch (Exception e) {
				return null;
			}
		}

		private static List<RouteInfo> routes() {
			RailwayData data = cached;
			if (data == null) {
				return Collections.emptyList();
			}
			List<RouteInfo> out = new ArrayList<>();
			for (Route route : new ArrayList<>(data.routes)) {
				List<Long> platformIds = new ArrayList<>();
				if (route.platformIds != null) {
					for (Route.RoutePlatform routePlatform : route.platformIds) {
						platformIds.add(routePlatform.platformId);
					}
				}
				out.add(new RouteInfo(route.id, route.name, route.color, platformIds));
			}
			return out;
		}

		private static List<DepotInfo> depots() {
			RailwayData data = cached;
			if (data == null) {
				return Collections.emptyList();
			}
			List<DepotInfo> out = new ArrayList<>();
			for (Depot depot : new ArrayList<>(data.depots)) {
				int minX;
				int minZ;
				int maxX;
				int maxZ;
				BlockPos center;
				if (depot.corner1 != null && depot.corner2 != null) {
					int x1 = depot.corner1.getA();
					int z1 = depot.corner1.getB();
					int x2 = depot.corner2.getA();
					int z2 = depot.corner2.getB();
					minX = Math.min(x1, x2);
					minZ = Math.min(z1, z2);
					maxX = Math.max(x1, x2);
					maxZ = Math.max(z1, z2);
					center = new BlockPos((minX + maxX) / 2, 0, (minZ + maxZ) / 2);
				} else {
					center = depot.getCenter();
					if (center == null) {
						warnMissingDepot(depot.id, depot.name);
						continue;
					}
					minX = center.getX() - 3;
					minZ = center.getZ() - 3;
					maxX = center.getX() + 3;
					maxZ = center.getZ() + 3;
				}

				List<Long> routeIds = depot.routeIds == null
						? Collections.<Long>emptyList() : new ArrayList<>(depot.routeIds);
				out.add(new DepotInfo(depot.id, depot.name, depot.color,
						minX, minZ, maxX, maxZ, center, routeIds));
			}
			return out;
		}

		/**
		 * 轨道表。3.6.x 起有公开的 {@code getRailsMap()}，但 3.2.x（1.16.5 / 1.18.2）没有，
		 * 两代的轨道表都放在私有字段 {@code rails} 里，所以统一走反射，一份代码覆盖全部 3.x。
		 */
		private static final Field railsField = findField(RailwayData.class, "rails");

		private static Map<BlockPos, Map<BlockPos, MtrRailGeometry>> rails() {
			RailwayData data = cached;
			if (data == null) {
				return null;
			}
			for (int attempt = 0; attempt < 3; attempt++) {
				try {
					Map<BlockPos, Map<BlockPos, Rail>> railData = railsOf(data);
					if (railData == null) {
						return null;
					}
					Map<BlockPos, Map<BlockPos, MtrRailGeometry>> out = new HashMap<>();
					for (Map.Entry<BlockPos, Map<BlockPos, Rail>> entry : railData.entrySet()) {
						Map<BlockPos, MtrRailGeometry> row = new HashMap<>();
						for (Map.Entry<BlockPos, Rail> neighbor : entry.getValue().entrySet()) {
							row.put(neighbor.getKey(), wrap(neighbor.getValue()));
						}
						out.put(entry.getKey(), row);
					}
					return out;
				} catch (Exception ignored) {
					// 换个时机再试
				}
			}
			return null;
		}

		@SuppressWarnings("unchecked")
		private static Map<BlockPos, Map<BlockPos, Rail>> railsOf(RailwayData data) throws Exception {
			if (railsField == null) {
				return null;
			}
			Object value = railsField.get(data);
			return value instanceof Map ? (Map<BlockPos, Map<BlockPos, Rail>>) value : null;
		}

		private static MtrRailGeometry wrap(Rail rail) {
			return new MtrRailGeometry() {
				@Override
				public double length() {
					return rail.getLength();
				}

				@Override
				public double[] position(double distance) {
					Vec3 pos = rail.getPosition(distance);
					return new double[]{pos.x, pos.z};
				}
			};
		}

		/** Siding.trains / Train.ridingEntities / TrainServer.routeId 都是 MTR 内部字段，反射读 */
		private static final Field sidingTrainsField = findField(Siding.class, "trains");
		private static final Field trainRidingEntitiesField = findField(Train.class, "ridingEntities");
		private static final Field trainServerRouteIdField = findField(TrainServer.class, "routeId");

		private static List<TrainInfo> trains() {
			RailwayData data = cached;
			if (data == null) {
				return Collections.emptyList();
			}
			Map<Long, Platform> platformMap = new HashMap<>();
			for (Platform platform : data.platforms) {
				platformMap.put(platform.id, platform);
			}
			Map<Long, Route> routeMap = new HashMap<>();
			for (Route route : data.routes) {
				routeMap.put(route.id, route);
			}

			List<TrainInfo> out = new ArrayList<>();
			Set<Siding> sidings;
			try {
				sidings = new HashSet<>(data.sidings);
			} catch (Exception e) {
				return out;
			}
			for (Siding siding : sidings) {
				List<TrainServer> sidingTrains;
				try {
					@SuppressWarnings("unchecked")
					Set<TrainServer> trains = (Set<TrainServer>) sidingTrainsField.get(siding);
					sidingTrains = new ArrayList<>(trains);
				} catch (Exception e) {
					continue;
				}
				for (TrainServer train : sidingTrains) {
					try {
						TrainInfo info = describe(train, data, platformMap, routeMap);
						if (info != null) {
							out.add(info);
						}
					} catch (Exception e) {
						MtrMapCommon.LOGGER.debug("处理列车数据失败: {}", train.trainId, e);
					}
				}
			}
			return out;
		}

		private static TrainInfo describe(TrainServer train, RailwayData data,
				Map<Long, Platform> platformMap, Map<Long, Route> routeMap) {
			double[] pos = trainPosition(train);
			if (pos == null) {
				return null;
			}

			long routeId = 0;
			try {
				routeId = trainServerRouteIdField.getLong(train);
			} catch (Exception ignored) {
				// 拿不到就当作没有线路，速度与位置照常显示
			}
			String routeName = "";
			int routeColor = 0xFFCCCCCC;
			String destination = "";
			Route route = routeMap.get(routeId);
			if (route != null) {
				routeName = route.name == null ? "" : route.name;
				routeColor = route.color;
				destination = resolveDestination(route, platformMap, data);
			}

			List<UUID> riding = new ArrayList<>();
			try {
				@SuppressWarnings("unchecked")
				Set<UUID> ridingEntities = (Set<UUID>) trainRidingEntitiesField.get(train);
				if (ridingEntities != null) {
					riding.addAll(ridingEntities);
				}
			} catch (Exception ignored) {
				// 乘客信息不可用时只影响人数与名单
			}

			return new TrainInfo(train.trainId == null ? "" : train.trainId, pos[0], pos[1],
					train.getIsOnRoute(), train.trainCars,
					// MTR 3.x 速度单位是格/tick（1 格 = 1 米），换算 km/h：* 20 tick/s * 3.6
					Math.round(train.getSpeed() * 20 * 3.6),
					routeId, routeName, routeColor, destination, riding.size(), riding);
		}

		/**
		 * 复现 MTR {@code Train.getRoutePosition(0, 0)} 的算法，返回车头所在位置。
		 * 前提：path.get(i) 为第 i 段轨道，累计距离可由各段轨长累加得到。
		 */
		private static double[] trainPosition(Train train) {
			try {
				List<PathData> path = train.path;
				if (path == null || path.isEmpty()) {
					return null;
				}
				double progress = train.getRailProgress();
				int segment = Math.max(0, Math.min(path.size() - 1, train.getIndex(progress, false)));
				double local = progress;
				for (int i = 0; i < segment; i++) {
					local -= path.get(i).rail.getLength();
				}
				Rail rail = path.get(segment).rail;
				if (rail == null) {
					return null;
				}
				double length = rail.getLength();
				local = Math.max(0, Math.min(local, length));
				Vec3 position = rail.getPosition(local);
				return new double[]{position.x, position.z};
			} catch (Exception e) {
				return null;
			}
		}

		/** 解析线路终点站：优先取自定义终点，否则取终点站名 */
		private static String resolveDestination(Route route, Map<Long, Platform> platformMap, RailwayData data) {
			try {
				String custom = route.getDestination(route.platformIds.size() - 1);
				if (custom != null && !custom.isEmpty()) {
					return custom;
				}
				long lastPlatformId = route.getLastPlatformId();
				Platform platform = platformMap.get(lastPlatformId);
				if (platform != null) {
					Station station = RailwayData.getStation(data.stations, data.dataCache, platform.getMidPos());
					if (station != null && station.name != null && !station.name.isEmpty()) {
						return station.name;
					}
				}
			} catch (Exception e) {
				MtrMapCommon.LOGGER.debug("解析线路终点失败", e);
			}
			return "";
		}
	}
	//?}

	/** 已提醒过缺少坐标的车厂（避免每 3 秒刷屏） */
	private static final Set<Long> warnedDepots = ConcurrentHashMap.newKeySet();

	private static void warnMissingDepot(long id, String name) {
		if (warnedDepots.add(id)) {
			MtrMapCommon.LOGGER.warn("车厂 [{}] 没有 corner 和 center 坐标，已跳过（仅提醒一次）", name);
		}
	}

	/**
	 * 沿继承链找字段：MTR 把这些字段常常声明在父类（Schema）上，
	 * getDeclaredField 只看本类，所以必须自己往上找。找不到返回 null。
	 */
	private static Field findField(Class<?> type, String name) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				// 继续往父类找
			}
		}
		return null;
	}
}
