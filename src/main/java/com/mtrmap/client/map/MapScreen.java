package com.mtrmap.client.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mtrmap.client.render.GuiSink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内地图窗口（F6 打开）。
 *
 * <p>底图是自研世界地图的瓦片（{@code /api/worldmap/<z>/<x>_<y>.png}），
 * 上面叠 MTR 线网、车站、车厂、列车与玩家；交互与网页地图一致：
 * 拖拽平移、滚轮缩放、点车站看详情、路径查询并把路线同步到游戏内导航。
 *
 * <p>所有坐标都用世界坐标（x = 东、z = 南），屏幕换算：
 * {@code sx = width/2 + (x - centerX) * scale}，与网页的 worldToCanvas 同一套公式。
 */
public class MapScreen extends Screen {

	// ===== 相机 =====
	private static final double MIN_SCALE = 0.02;
	private static final double MAX_SCALE = 4.0;
	/** 一屏需要绘制的瓦片上限，超过就只画纯色底，避免缩得太小时铺满屏幕 */
	private static final int MAX_TILES = 480;

	private double centerX;
	private double centerZ;
	private double scale = 0.15;
	private boolean viewInitialized;

	// ===== 交互 =====
	private boolean dragging;
	private double dragLastX;
	private double dragLastY;
	private boolean dragMoved;

	// ===== 界面状态 =====
	private boolean showDepots = true;
	private boolean depotsInitialized;
	private boolean dark = true;
	private boolean english;
	/** 搜索：0 = 车站，1 = 线路 */
	private boolean searchRouteMode;
	private String searchText = "";
	private boolean searchFocused;
	private int searchCursor; // 光标位置（在 searchText 中的索引）
	private int searchIndex;
	private final List<Object> searchHits = new ArrayList<>();

	// ===== 详情 / 路径 =====
	private MapModel.Station detailStation;
	private MapModel.Route detailRoute;
	/** 左侧堆叠上一帧占用的范围，用来挡住「点面板却选中了背后的车站」 */
	private int leftStackRight;
	private int leftStackBottom;
	/** 详情面板上一帧的高度（路径面板要排在它下面） */
	private int detailPanelH;
	/** 路径查询模式 */
	private boolean routeMode;
	private long routeStartId = -1;
	private long routeEndId = -1;
	private boolean routeFromMyLocation;
	private double myX;
	private double myZ;
	private boolean hasMyLocation;
	private List<RoutePlanner.Option> routeOptions = new ArrayList<>();
	private int activeRouteTab;
	private String routeMessage;
	private RoutePlanner.StartWalk startWalk;
	private String lastNavRev;

	/** 悬停提示 */
	private String hoverText;
	private double hoverX;
	private double hoverY;

	/** 行程记录弹窗 */
	private boolean tripsOpen;
	private boolean tripsLoading;
	private JsonArray trips = new JsonArray();

	/** 导出图片弹窗 */
	private boolean exportOpen;
	private boolean exporting;
	private String exportFormat = "png";
	private int exportQuality = 92;
	private boolean qualityDragging;

	/** 保存结果提示 */
	private String toastText;
	private int toastColor = 0xFF9BE59B;
	private long toastUntil;

	private static final int PAD = 6;
	private static final int ROW = 18;
	/** 工具栏按钮边长：11 个按钮竖排，要保证在小窗口（GUI 缩放 4）里也放得下 */
	private static final int TOOL_W = 16;
	/** 详情 / 路径面板宽度（两者共用一个宽度，画与点击才能对得上） */
	private static final int PANEL_W = 186;
	/** 面板里一行的行高 */
	private static final int LINE_H = 10;
	/** 路径方案每一步占的高度（两步文字） */
	private static final int STEP_H = 17;
	/** 线路一览一排占的高度 */
	private static final int ROUTE_ROW_H = 24;

	public MapScreen() {
		super(Component.translatable("key.mtrmap.map_title"));
	}

	// ===== 生命周期 =====

	@Override
	protected void init() {
		super.init();
		MapDataClient.open();
		if (!viewInitialized) {
			viewInitialized = true;
			fitView();
		}
		if (!depotsInitialized && MapDataClient.model().loaded) {
			depotsInitialized = true;
			showDepots = MapDataClient.model().showDepots;
		}
	}

	@Override
	public void removed() {
		MapDataClient.close();
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** 是否处于路径查询（选起点/终点）状态 */
	public boolean isRouteMode() {
		return routeMode;
	}

	// ===== 相机换算 =====

	private double worldToScreenX(double x) {
		return width / 2.0 + (x - centerX) * scale;
	}

	private double worldToScreenY(double z) {
		return height / 2.0 + (z - centerZ) * scale;
	}

	private double screenToWorldX(double sx) {
		return centerX + (sx - width / 2.0) / scale;
	}

	private double screenToWorldZ(double sy) {
		return centerZ + (sy - height / 2.0) / scale;
	}

	private void fitView() {
		MapModel model = MapDataClient.model();
		double minX = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		boolean any = false;
		for (MapModel.Station station : model.stations) {
			minX = Math.min(minX, station.x);
			maxX = Math.max(maxX, station.x);
			minZ = Math.min(minZ, station.z);
			maxZ = Math.max(maxZ, station.z);
			any = true;
		}
		for (MapModel.Depot depot : model.depots) {
			minX = Math.min(minX, depot.centerX);
			maxX = Math.max(maxX, depot.centerX);
			minZ = Math.min(minZ, depot.centerZ);
			maxZ = Math.max(maxZ, depot.centerZ);
			any = true;
		}
		if (!any) {
			centerX = 0;
			centerZ = 0;
			scale = 0.15;
			return;
		}
		double w = Math.max(1, maxX - minX);
		double h = Math.max(1, maxZ - minZ);
		int padding = 90;
		double fit = Math.min((width - padding * 2.0) / w, (height - padding * 2.0) / h);
		scale = clamp(fit <= 0 || Double.isNaN(fit) ? 0.15 : fit, MIN_SCALE, MAX_SCALE);
		centerX = (minX + maxX) / 2;
		centerZ = (minZ + maxZ) / 2;
	}

	private void zoomAt(double mouseX, double mouseY, double factor) {
		double next = clamp(scale * factor, MIN_SCALE, MAX_SCALE);
		if (next == scale) {
			return;
		}
		double wx = screenToWorldX(mouseX);
		double wz = screenToWorldZ(mouseY);
		scale = next;
		centerX = wx - (mouseX - width / 2.0) / scale;
		centerZ = wz - (mouseY - height / 2.0) / scale;
	}

	// ===== 渲染 =====

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		MapModel model = MapDataClient.model();
		if (!depotsInitialized && model.loaded) {
			depotsInitialized = true;
			showDepots = model.showDepots;
		}
		TileTextures.beginFrame();
		GuiSink sink = new GuiSink(graphics);
		hoverText = null;

		drawTileBackground(sink);
		drawDepots(sink, model);
		drawRoutes(sink, model);
		drawHighlight(sink, model);
		drawStations(sink, model, mouseX, mouseY);
		if (!routeMode) {
			drawTrains(sink, model);
		}
		drawPlayers(sink, model);
		if (routeMode) {
			drawRouteSelection(sink, model);
		}

		drawLeftStack(sink, mouseX, mouseY);
		drawToolbar(sink, mouseX, mouseY);
		drawDetailPanel(sink);
		drawRoutePanel(sink, mouseX, mouseY);
		if (hoverText != null) {
			drawTooltip(sink, hoverText, (int) hoverX, (int) hoverY);
		}
		// 行程记录 / 导出弹窗画在最上层
		drawTripsDialog(sink);
		drawExportDialog(sink);
		drawToast(sink);
		// 不调用 super.render：原版 Screen.render 会画一遍背景/组件，把地图盖掉。
		// 本窗口没有任何原版控件，绘制全部由上面的 GuiSink 完成。
	}

	// ===== 底图：自研世界地图瓦片 =====

	private void drawTileBackground(GuiSink sink) {
		WorldMapBridge.Settings wm = WorldMapBridge.settings();
		if (!wm.ok()) {
			sink.fill(0, 0, width, height, dark ? 0xFF14141C : 0xFFF2F2F6);
			drawBaseMapHint(sink, wm.detail);
			return;
		}
		double tileZoomExact = wm.maxZoom + Math.log(scale) / Math.log(2);
		int tileZoom = (int) Math.floor(tileZoomExact);
		tileZoom = Math.max(wm.minZoom, Math.min(wm.maxZoom, tileZoom));
		double tileScale = Math.pow(2, tileZoom - wm.maxZoom);
		double tileScreenSize = wm.tileSize * scale / tileScale;

		double worldLeft = screenToWorldX(0);
		double worldRight = screenToWorldX(width);
		double worldTop = screenToWorldZ(0);
		double worldBottom = screenToWorldZ(height);
		long blocksPerTile = Math.round(wm.tileSize / tileScale);
		int tx0 = (int) Math.floor(worldLeft / blocksPerTile);
		int tx1 = (int) Math.floor(worldRight / blocksPerTile);
		int ty0 = (int) Math.floor(worldTop / blocksPerTile);
		int ty1 = (int) Math.floor(worldBottom / blocksPerTile);
		long count = (long) (tx1 - tx0 + 1) * (ty1 - ty0 + 1);
		if (count > MAX_TILES) {
			sink.fill(0, 0, width, height, dark ? 0xFF14141C : 0xFFF2F2F6);
			return;
		}
		// 底图底色：瓦片还没到之前先垫一层，避免闪烁
		sink.fill(0, 0, width, height, dark ? 0xFF0F0F16 : 0xFFE8E8EE);
		sink.flush();

		for (int ty = ty0; ty <= ty1; ty++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				ResourceLocation texture = TileTextures.get(tileZoom, tx, ty);
				if (texture == null) {
					continue;
				}
				double sx = worldToScreenX(tx * (double) blocksPerTile);
				double sy = worldToScreenY(ty * (double) blocksPerTile);
				// 相邻瓦片各多铺 0.5px，避免浮点截断出现缝隙
				sink.texture(texture, (float) sx, (float) sy,
						(float) (tileScreenSize + 0.5), (float) (tileScreenSize + 0.5));
			}
		}
	}

	/** 底图拿不到时，把原因写在屏幕中间（参数在后台重试，出问题也不阻塞渲染） */
	private void drawBaseMapHint(GuiSink sink, String detail) {
		if (detail == null || detail.isEmpty()) {
			return;
		}
		sink.text(font, s("世界地图底图不可用", "world map base layer unavailable"),
				width / 2f, height / 2f - 6, 0xFFFF8080, true);
		sink.text(font, detail, width / 2f, height / 2f + 8, 0xFFFFC080, true);
	}
	// ===== 线网 =====

	private void drawDepots(GuiSink sink, MapModel model) {
		if (!showDepots) {
			return;
		}
		for (MapModel.Depot depot : model.depots) {
			double x1 = worldToScreenX(Math.min(depot.x1, depot.x2));
			double y1 = worldToScreenY(Math.min(depot.z1, depot.z2));
			double x2 = worldToScreenX(Math.max(depot.x1, depot.x2));
			double y2 = worldToScreenY(Math.max(depot.z1, depot.z2));
			sink.fill((int) x1, (int) y1, (int) x2, (int) y2, alpha(depot.color, 0.6f));
			border(sink, (int) x1, (int) y1, (int) (x2 - x1), (int) (y2 - y1), alpha(depot.color, 0.9f));
			if (scale > 0.05) {
				String[] n = MapModel.splitName(depot.name);
				double cx = (x1 + x2) / 2;
				double cy = (y1 + y2) / 2;
				if (!n[1].isEmpty()) {
					sink.text(font, n[0], (float) cx, (float) (cy - 10), textColor(), true);
					small(sink, n[1], (float) (cx - font.width(n[1]) * 0.35), (float) (cy + 1), dimColor());
				} else {
					sink.text(font, n[0], (float) cx, (float) (cy - 4), textColor(), true);
				}
			}
			// 站台到车厂的出入库连线
			if (depot.path != null && depot.path.size() >= 2) {
				drawPolyline(sink, depot.path, alpha(depot.color, 0.8f), 1);
			} else if (depot.firstPlatformX != null) {
				double sx = worldToScreenX(depot.firstPlatformX);
				double sy = worldToScreenY(depot.firstPlatformZ);
				double ex = worldToScreenX(depot.centerX);
				double ey = worldToScreenY(depot.centerZ);
				line(sink, sx, sy, ex, ey, alpha(depot.color, 0.8f), 1);
			}
		}
	}

	private void drawRoutes(GuiSink sink, MapModel model) {
		for (MapModel.Route route : model.routes) {
			if (route.stations.size() < 2) {
				continue;
			}
			int color = alpha(route.color, 0.9f);
			int thickness = (int) Math.max(2, 4 * Math.sqrt(scale));
			for (int i = 0; i < route.stations.size() - 1; i++) {
				List<double[]> seg = i < route.paths.size() ? route.paths.get(i) : null;
				if (seg != null && seg.size() >= 2) {
					drawPolyline(sink, seg, color, thickness);
				} else {
					MapModel.Station a = model.station(route.stations.get(i));
					MapModel.Station b = model.station(route.stations.get(i + 1));
					if (a == null || b == null) {
						continue;
					}
					line(sink, worldToScreenX(a.x), worldToScreenY(a.z),
							worldToScreenX(b.x), worldToScreenY(b.z), color, thickness);
				}
			}
		}
	}

	private void drawStations(GuiSink sink, MapModel model, int mouseX, int mouseY) {
		double radius = routeMode ? Math.max(6, 8 * Math.sqrt(scale)) : Math.max(4, 6 * Math.sqrt(scale));
		for (MapModel.Station station : model.stations) {
			if (station.isInterchange() && station.hasBounds()) {
				double[] capsule = capsule(station, radius * 2);
				if (capsule != null) {
					drawCapsule(sink, capsule, 0xFFFFFFFF, alpha(station.color, 1f), 2);
				}
			} else {
				double cx = worldToScreenX(station.x);
				double cy = worldToScreenY(station.z);
				disc(sink, cx, cy, radius, 0xFFFFFFFF, alpha(station.color, 1f), 2);
			}
		}
		// 站名：缩得太小不画；换乘站优先占位，重叠的跳过
		if (scale <= 0.05) {
			return;
		}
		List<int[]> placed = new ArrayList<>();
		drawStationNames(sink, model, radius, true, placed);
		drawStationNames(sink, model, radius, false, placed);

		// 悬停：找鼠标附近的车站
		MapModel.Station hovered = pickStation(model, mouseX, mouseY, radius);
		if (hovered != null) {
			hoverText = stationLabel(hovered);
			hoverX = mouseX + 10;
			hoverY = mouseY + 6;
		}
	}

	private void drawStationNames(GuiSink sink, MapModel model, double radius, boolean interchange, List<int[]> placed) {
		for (MapModel.Station station : model.stations) {
			if (station.isInterchange() != interchange) {
				continue;
			}
			if (placed.size() >= 400) {
				return;
			}
			String[] n = MapModel.splitName(station.name);
			if (n[0].isEmpty()) {
				continue;
			}
			double labelX;
			double labelTop;
			if (station.isInterchange() && station.hasBounds()) {
				double[] capsule = capsule(station, radius * 2);
				if (capsule == null) {
					continue;
				}
				labelX = capsule[0];
				labelTop = capsule[1] - (capsule[4] > 0 ? capsule[3] + capsule[2] : capsule[2]);
			} else {
				labelX = worldToScreenX(station.x);
				labelTop = worldToScreenY(station.z) - radius;
			}
			int mainW = font.width(n[0]);
			int transW = n[1].isEmpty() ? 0 : Math.round(font.width(n[1]) * 0.7f);
			int w = Math.max(mainW, transW);
			int baseline = (int) (labelTop - 4);
			int left = (int) (labelX - w / 2.0 - 2);
			int right = (int) (labelX + w / 2.0 + 2);
			int top = baseline - 10;
			int bottom = baseline + (n[1].isEmpty() ? 0 : 11);
			if (overlaps(placed, left, right, top, bottom)) {
				continue;
			}
			placed.add(new int[]{left, right, top, bottom});
			sink.text(font, n[0], (float) labelX, (float) (baseline - 9), textColor(), true);
			if (!n[1].isEmpty()) {
				small(sink, n[1], (float) (labelX - transW / 2.0), (float) baseline, dimColor());
			}
		}
	}

	private boolean overlaps(List<int[]> placed, int left, int right, int top, int bottom) {
		for (int[] r : placed) {
			if (left < r[1] && right > r[0] && top < r[3] && bottom > r[2]) {
				return true;
			}
		}
		return false;
	}

	/** 换乘站胶囊几何：{cx, cy, r, half, vertical(1/0)} */
	private double[] capsule(MapModel.Station station, double minThick) {
		if (!station.hasBounds()) {
			return null;
		}
		double x1 = worldToScreenX(station.bx1);
		double y1 = worldToScreenY(station.bz1);
		double x2 = worldToScreenX(station.bx2);
		double y2 = worldToScreenY(station.bz2);
		double cx = (x1 + x2) / 2;
		double cy = (y1 + y2) / 2;
		double w = Math.abs(x2 - x1);
		double h = Math.abs(y2 - y1);
		boolean vertical = h > w;
		double r = Math.max(minThick / 2, (vertical ? w : h) / 2);
		double half = Math.max((vertical ? h : w) / 2 - r, r * 0.8);
		return new double[]{cx, cy, r, half, vertical ? 1 : 0};
	}

	private void drawTrains(GuiSink sink, MapModel model) {
		double size = Math.max(7, 11 * Math.sqrt(scale));
		for (MapModel.Train train : model.trains) {
			double cx = worldToScreenX(train.x);
			double cy = worldToScreenY(train.z);
			int color = alpha(train.routeColor, 0.95f);
			sink.fill((int) (cx - size / 2), (int) (cy - size / 2),
					(int) (cx + size / 2), (int) (cy + size * 0.2), 0xF21E1E37);
			border(sink, (int) (cx - size / 2), (int) (cy - size / 2), (int) size, (int) (size * 0.7), color);
			sink.fill((int) (cx - size * 0.2), (int) (cy - size * 0.18),
					(int) (cx + size * 0.2), (int) (cy), color);
		}
	}

	private void drawPlayers(GuiSink sink, MapModel model) {
		double size = Math.max(10, 14 * Math.sqrt(scale));
		for (MapModel.Player player : model.players) {
			double cx = worldToScreenX(player.x);
			double cy = worldToScreenY(player.z);
			sink.fill((int) (cx - size / 2), (int) (cy - size / 2), (int) (cx + size / 2), (int) (cy + size / 2), 0xFF50FA7B);
			border(sink, (int) (cx - size / 2), (int) (cy - size / 2), (int) size, (int) size, 0xFF2DA84F);
			if (scale > 0.05) {
				sink.text(font, player.name, (float) cx, (float) (cy + size / 2 + 3), 0xFF50FA7B, true);
			}
		}
		if (hasMyLocation) {
			double cx = worldToScreenX(myX);
			double cy = worldToScreenY(myZ);
			double r = Math.max(6, 9 * Math.sqrt(scale));
			disc(sink, cx, cy, r, 0xFF3D8BFD, 0xFFFFFFFF, 2);
			if (scale > 0.05) {
				sink.text(font, s("我的位置", "My location"), (float) cx, (float) (cy + r + 3), 0xFF9EC5FF, true);
			}
		}
	}

	private void drawHighlight(GuiSink sink, MapModel model) {
		if (detailStation != null) {
			double cx = worldToScreenX(detailStation.x);
			double cy = worldToScreenY(detailStation.z);
			double r = Math.max(6, 9 * Math.sqrt(scale)) + 4;
			disc(sink, cx, cy, r, 0x00000000, 0xFFFFD040, 3);
		}
		if (detailRoute != null) {
			int base = (int) Math.max(2, 4 * Math.sqrt(scale));
			for (int i = 0; i < detailRoute.stations.size() - 1; i++) {
				List<double[]> seg = i < detailRoute.paths.size() ? detailRoute.paths.get(i) : null;
				if (seg != null && seg.size() >= 2) {
					drawPolyline(sink, seg, 0x66FFD040, base * 3);
					drawPolyline(sink, seg, 0xFFFFD040, base + 2);
				} else {
					MapModel.Station a = model.station(detailRoute.stations.get(i));
					MapModel.Station b = model.station(detailRoute.stations.get(i + 1));
					if (a != null && b != null) {
						line(sink, worldToScreenX(a.x), worldToScreenY(a.z),
								worldToScreenX(b.x), worldToScreenY(b.z), 0x66FFD040, base * 3);
						line(sink, worldToScreenX(a.x), worldToScreenY(a.z),
								worldToScreenX(b.x), worldToScreenY(b.z), 0xFFFFD040, base + 2);
					}
				}
			}
		}
	}

	private void drawRouteSelection(GuiSink sink, MapModel model) {
		MapModel.Station start = model.station(routeStartId);
		MapModel.Station end = model.station(routeEndId);
		if (start != null) {
			marker(sink, start, 0xFF50FA7B, s("起", "A"));
		}
		if (end != null) {
			marker(sink, end, 0xFFFF6B6B, s("终", "B"));
		}
		if (hasMyLocation && start != null && routeFromMyLocation) {
			line(sink, worldToScreenX(myX), worldToScreenY(myZ),
					worldToScreenX(start.x), worldToScreenY(start.z), 0xCC9AA0A6, 2);
		}
	}

	private void marker(GuiSink sink, MapModel.Station station, int color, String label) {
		double cx = worldToScreenX(station.x);
		double cy = worldToScreenY(station.z);
		double r = Math.max(7, 10 * Math.sqrt(scale));
		disc(sink, cx, cy, r, alpha(color, 0.85f), 0xFFFFFFFF, 2);
		sink.text(font, label, (float) cx, (float) (cy - 4), 0xFF101018, false);
	}

	// ===== 左侧堆叠：搜索 / 状态 / 线路一览 =====

	private void drawLeftStack(GuiSink sink, int mouseX, int mouseY) {
		MapModel model = MapDataClient.model();
		int x = 8;
		int y = 8;
		int w = leftStackWidth();

		// 搜索框
		int typeW = 44;
		panel(sink, x, y, w, 20);
		sink.fill(x + 2, y + 2, x + 2 + typeW, y + 18, dark ? 0xFF2A2A3C : 0xFFE4E4EC);
		sink.text(font, searchRouteMode ? s("线路", "Line") : s("车站", "Station"),
				x + 2 + typeW / 2f, y + 6, textColor(), false);
		int inputX = x + 2 + typeW + 2;
		int inputW = w - (inputX - x) - 2;
		if (searchFocused) {
			border(sink, inputX, y + 2, inputW, 16, 0xFF7EC8E3);
		}
		String shown = searchText.isEmpty() && !searchFocused
				? s("搜索车站", "Search stations")
				: searchText;
		boolean placeholder = searchText.isEmpty() && !searchFocused;
		sink.textLeft(font, shown, inputX + 3, y + 6, placeholder ? dimColor() : textColor(), false);
		if (searchFocused) {
			int caret = inputX + 3 + font.width(searchText.substring(0, Math.min(searchCursor, searchText.length())));
			sink.fill(caret, y + 4, caret + 1, y + 16, textColor());
		}
		updateSearchHits(model);
		y += 22;

		// 候选词
		if (searchFocused && !searchHits.isEmpty()) {
			int rows = Math.min(8, searchHits.size());
			int h = rows * ROW;
			panel(sink, x, y, w, h);
			for (int i = 0; i < rows; i++) {
				int ry = y + i * ROW;
				if (i == searchIndex) {
					sink.fill(x + 1, ry + 1, x + w - 1, ry + ROW - 1, dark ? 0xFF35405C : 0xFFD6E4FF);
				}
				sink.textLeft(font, hitLabel(searchHits.get(i)), x + 4, ry + 5, textColor(), false);
			}
			y += h + 2;
		} else if (searchFocused && !searchText.isEmpty()) {
			panel(sink, x, y, w, ROW);
			sink.textLeft(font, s("无匹配结果", "No results"), x + 4, y + 5, dimColor(), false);
			y += ROW + 2;
		}

		// 状态 + 图例
		int infoH = 31;
		panel(sink, x, y, w, infoH);
		String status = model.loaded
				? s("车站 ", "Stations ") + model.stations.size()
					+ s(" 线路 ", " lines ") + model.routes.size()
					+ s(" 车厂 ", " depots ") + model.depots.size()
					+ s(" 列车 ", " trains ") + model.trains.size()
				: s("正在加载...", "Loading...");
		sink.textLeft(font, fit(status, w - 12), x + 4, y + 3, textColor(), false);
		int lx = x + 4;
		int ly = y + 18;
		lx = legend(sink, lx, ly, 0xFFFFFFFF, alpha(0xFF7EC8E3, 1f), s("车站", "Station"));
		lx = legend(sink, lx, ly, 0xFFFFFFFF, 0xFFFFD040, s("换乘", "Interchange"));
		legend(sink, lx, ly, 0xFF50FA7B, 0xFF2DA84F, s("玩家", "Player"));
		y += infoH + 4;

		// 线路一览：每排最多 4 个
		if (!model.routes.isEmpty()) {
			int cols = Math.min(4, model.routes.size());
			int cellW = Math.max(100, (w - (cols - 1) * 4) / cols);
			int rows = (model.routes.size() + cols - 1) / cols;
			int maxRows = Math.min(rows, 7);
			int listH = maxRows * ROUTE_ROW_H + 6;
			panel(sink, x, y, w, listH);
			for (int r = 0; r < maxRows; r++) {
				for (int c = 0; c < cols; c++) {
					int index = r * cols + c;
					if (index >= model.routes.size()) {
						break;
					}
					MapModel.Route route = model.routes.get(index);
					int cx0 = x + 4 + c * (cellW + 4);
					int cy0 = y + 4 + r * ROUTE_ROW_H;
					sink.fill(cx0, cy0 + 1, cx0 + 3, cy0 + 19, alpha(route.color, 1f));
					String[] n = MapModel.splitName(route.name);
					sink.textLeft(font, fit(n[0], cellW - 8), cx0 + 6, cy0, textColor(), false);
					if (!n[1].isEmpty()) {
						small(sink, fit(n[1], cellW - 8), cx0 + 6, cy0 + 10, dimColor());
					}
					String ends = routeEnds(model, route);
					if (!ends.isEmpty()) {
						small(sink, fit(ends, cellW - 8), cx0 + 6, cy0 + 17, dimColor());
					}
				}
			}
			y += listH;
		}
		// 记下占用范围：点击落在这一块里时不要穿透到地图去选站
		leftStackRight = x + w;
		leftStackBottom = y + 4;
	}

	private int leftStackWidth() {
		return Math.min(340, Math.max(170, width - 8 - 8 - TOOL_W - 12));
	}

	private String routeEnds(MapModel model, MapModel.Route route) {
		if (route.stations.size() < 2) {
			return "";
		}
		MapModel.Station a = model.station(route.stations.get(0));
		MapModel.Station b = model.station(route.stations.get(route.stations.size() - 1));
		if (a == null || b == null) {
			return "";
		}
		return MapModel.splitName(a.name)[0] + "~" + MapModel.splitName(b.name)[0];
	}

	private int legend(GuiSink sink, int x, int y, int fill, int outline, String label) {
		sink.fill(x, y, x + 8, y + 8, fill);
		border(sink, x, y, 8, 8, outline);
		sink.textLeft(font, label, x + 10, y + 1, dimColor(), false);
		return x + 12 + font.width(label) + 8;
	}

	// ===== 右侧工具栏 =====

	private List<Btn> toolbarButtons() {
		List<Btn> buttons = new ArrayList<>();
		int x = width - 8 - TOOL_W;
		int totalRows = 11;
		int gap = 2;
		int totalH = totalRows * (TOOL_W + gap) - gap;
		int y = (height - totalH) / 2;
		buttons.add(new Btn("route", x, y, s("⇄", "⇄"), s("路径查询", "Route planner"), routeMode));
		y += TOOL_W + gap;
		buttons.add(new Btn("theme", x, y, dark ? "☾" : "☀", s("夜间模式", "Night mode"), false));
		y += TOOL_W + gap;
		buttons.add(new Btn("lang", x, y, "EN", s("English", "中文"), english));
		y += TOOL_W + gap;
		buttons.add(new Btn("export", x, y, "↓", s("导出图片", "Export image"), false));
		y += TOOL_W + gap;
		buttons.add(new Btn("depot", x, y, "▣", s("显示/隐藏车厂", "Toggle depots"), showDepots));
		y += TOOL_W + gap;
		buttons.add(new Btn("zoomIn", x, y, "+", s("放大", "Zoom in"), false));
		y += TOOL_W + gap;
		buttons.add(new Btn("zoomOut", x, y, "-", s("缩小", "Zoom out"), false));
		y += TOOL_W + gap;
		buttons.add(new Btn("reset", x, y, "⊙", s("重置视图", "Reset view"), false));
		y += TOOL_W + gap;
		buttons.add(new Btn("my", x, y, "📍", s("以我当前位置为起点", "Start from my location"), routeFromMyLocation));
		y += TOOL_W + gap;
		buttons.add(new Btn("trips", x, y, "🗒", s("行程记录", "Trip records"), tripsOpen));
		y += TOOL_W + gap;
		buttons.add(new Btn("close", x, y, "×", s("关闭 (F6)", "Close (F6)"), false));
		return buttons;
	}

	private void drawToolbar(GuiSink sink, int mouseX, int mouseY) {
		for (Btn button : toolbarButtons()) {
			boolean hover = button.contains(mouseX, mouseY);
			int bg = button.active ? 0xFF3B5180 : (hover ? 0xFF35405C : (dark ? 0xE61E1E2E : 0xF2FFFFFF));
			sink.fill(button.x, button.y, button.x + TOOL_W, button.y + TOOL_W, bg);
			border(sink, button.x, button.y, TOOL_W, TOOL_W, dark ? 0xFF3A3A4A : 0xFFBBBBCC);
			sink.text(font, button.label, button.x + TOOL_W / 2f, button.y + (TOOL_W - 8) / 2f, textColor(), false);
			if (hover && hoverText == null) {
				hoverText = button.tooltip;
				hoverX = button.x - 8 - font.width(button.tooltip);
				hoverY = button.y + 4;
			}
		}
	}

	// ===== 详情面板 =====

	private void drawDetailPanel(GuiSink sink) {
		if (detailStation == null && detailRoute == null) {
			detailPanelH = 0;
			return;
		}
		MapModel model = MapDataClient.model();
		int w = PANEL_W;
		int x = width - 8 - TOOL_W - 6 - w;
		int y = 8;
		List<String> lines = new ArrayList<>();
		String title;
		if (detailStation != null) {
			String[] n = MapModel.splitName(detailStation.name);
			title = english && !n[1].isEmpty() ? n[1] : n[0];
			lines.add(s("坐标 ", "Coord ") + (int) detailStation.x + ", " + (int) detailStation.z);
			lines.add(s("经过线路", "Lines"));
			int shown = 0;
			for (MapModel.Route route : model.routes) {
				if (route.stations.contains(detailStation.id)) {
					if (shown >= 8) {
						break;
					}
					String[] rn = MapModel.splitName(route.name);
					lines.add("· " + (english && !rn[1].isEmpty() ? rn[1] : rn[0]));
					shown++;
				}
			}
			if (shown == 0) {
				lines.add(s("暂无线路经过", "No lines"));
			}
		} else {
			String[] n = MapModel.splitName(detailRoute.name);
			title = english && !n[1].isEmpty() ? n[1] : n[0];
			String ends = routeEnds(model, detailRoute);
			if (!ends.isEmpty()) {
				lines.add(ends);
			}
			lines.add(s("共 ", "Stations ") + detailRoute.stations.size() + s(" 站", ""));
			int minutes = RoutePlanner.travelMinutes(model, detailRoute);
			if (minutes > 0) {
				lines.add(s("坐完全程约需 " + minutes + " 分钟", "Full trip ~" + minutes + " min"));
			}
			if (detailRoute.headway > 0) {
				lines.add(s("约 " + detailRoute.headway + " 分钟一班", "Every ~" + detailRoute.headway + " min"));
			} else {
				lines.add(s("班次间隔未知", "Headway unknown"));
			}
		}
		int h = 20 + lines.size() * LINE_H;
		detailPanelH = h;
		panel(sink, x, y, w, h);
		sink.fill(x + 5, y + 5, x + 8, y + h - 5, alpha(detailRoute != null ? detailRoute.color : detailStation.color, 1f));
		sink.textLeft(font, fit(title, w - 34), x + 12, y + 4, textColor(), false);
		sink.textLeft(font, "×", x + w - 11, y + 4, dimColor(), false);
		int cy = y + 17;
		for (String line : lines) {
			sink.textLeft(font, fit(line, w - 18), x + 12, cy, dimColor(), false);
			cy += LINE_H;
		}
	}

	// ===== 路径查询面板 =====

	private void drawRoutePanel(GuiSink sink, int mouseX, int mouseY) {
		if (!routeMode) {
			return;
		}
		MapModel model = MapDataClient.model();
		int w = PANEL_W;
		int x = width - 8 - TOOL_W - 6 - w;
		int y = routePanelY();
		List<String> lines = new ArrayList<>();
		String head = s("路径查询", "Route planner");
		String hint;
		if (routeStartId < 0) {
			hint = s("请选择起点", "Pick the start");
		} else if (routeEndId < 0) {
			hint = s("请选择终点", "Pick the destination");
		} else {
			MapModel.Station a = model.station(routeStartId);
			MapModel.Station b = model.station(routeEndId);
			hint = s("起点：", "From: ") + (a == null ? "-" : stationLabel(a))
					+ s("　终点：", "  To: ") + (b == null ? "-" : stationLabel(b));
		}
		if (startWalk != null) {
			String dir = startWalk.dirIndex == null ? "" : RoutePlanner.DIRECTIONS[startWalk.dirIndex];
			lines.add(s("从我的位置往" + dir + "步行 " + Math.round(startWalk.dist) + " 米到起点",
					"Walk " + Math.round(startWalk.dist) + "m " + dir + " to the start"));
		}
		if (routeMessage != null) {
			lines.add(routeMessage);
		}
		RoutePlanner.Option option = routeOptions.isEmpty() ? null
				: routeOptions.get(Math.min(activeRouteTab, routeOptions.size() - 1));
		// 步骤行数封顶：面板再高就要超出小窗口了（极复杂的换乘方案只显示前 7 步）
		int shownSteps = visibleStepCount();
		// 面板高度必须与下面的绘制顺序对齐：
		// 标题 4 / 提示 17 / 按钮行 29(高 13) / 若干提示行 / 方案标签页 11+2 / 概要 11 / 每步 18
		int h = routePanelHeight();
		panel(sink, x, y, w, h);
		sink.textLeft(font, head, x + 6, y + 4, textColor(), false);
		sink.textLeft(font, "×", x + w - 11, y + 4, dimColor(), false);
		sink.textLeft(font, fit(hint, w - 12), x + 6, y + 17, dimColor(), false);

		// 按钮行：立即查询 / 清除 / 我的位置
		int by = y + 29;
		int bh = 13;
		int bw = (w - 15) / 3;
		String[] labels = {s("立即查询", "Search"), s("清除", "Clear"), s("📍我的位置", "📍My location")};
		String[] ids = {"go", "clear", "my"};
		for (int i = 0; i < 3; i++) {
			int bx = x + 6 + i * (bw + 3);
			boolean enabled = !"go".equals(ids[i]) || (routeStartId >= 0 && routeEndId >= 0);
			int bg = "go".equals(ids[i]) && routeStartId >= 0 && routeEndId >= 0
					? 0xFF3B6FD4 : (dark ? 0xFF2A2A3C : 0xFFE4E4EC);
			sink.fill(bx, by, bx + bw, by + bh, bg);
			border(sink, bx, by, bw, bh, dark ? 0xFF3A3A4A : 0xFFBBBBCC);
			sink.text(font, fit(labels[i], bw - 4), bx + bw / 2f, by + 3, enabled ? textColor() : dimColor(), false);
		}
		int cy = by + 18;
		for (String line : lines) {
			sink.textLeft(font, fit(line, w - 12), x + 6, cy, dimColor(), false);
			cy += LINE_H;
		}
		if (routeOptions.isEmpty()) {
			return;
		}
		// 方案标签页
		int tabW = (w - 12) / routeOptions.size();
		for (int i = 0; i < routeOptions.size(); i++) {
			int tx = x + 6 + i * tabW;
			boolean active = i == Math.min(activeRouteTab, routeOptions.size() - 1);
			sink.fill(tx, cy, tx + tabW - 2, cy + 11, active ? 0xFF3B5180 : (dark ? 0xFF2A2A3C : 0xFFE4E4EC));
			sink.text(font, routeOptions.get(i).mode.label, tx + (tabW - 2) / 2f, cy + 1, textColor(), false);
		}
		cy += 13;
		// 概要
		String summary = s("总距离 ", "Dist ") + fmtDist(option.dist)
				+ s(" · 预计 ", " · ~") + fmtMinutes(option.timeSec)
				+ s(" · 换乘 ", " · transfers ") + option.transfers;
		sink.textLeft(font, fit(summary, w - 12), x + 6, cy, 0xFF7EC8E3, false);
		cy += 11;
		for (int stepIndex = 0; stepIndex < shownSteps; stepIndex++) {
			RoutePlanner.Step step = option.steps.get(stepIndex);
			if (step.walk) {
				MapModel.Station to = model.station(step.toStation);
				String dir = step.dirIndex == null ? "" : RoutePlanner.DIRECTIONS[step.dirIndex];
				sink.textLeft(font, fit(s("步行 " + Math.round(step.dist) + " 米 往" + dir,
						"Walk " + Math.round(step.dist) + "m " + dir), w - 20), x + 12, cy, dimColor(), false);
				sink.textLeft(font, fit(s("到 ", "to ") + (to == null ? "" : stationLabel(to)), w - 20),
						x + 12, cy + 9, dimColor(), false);
			} else {
				sink.fill(x + 6, cy + 1, x + 8, cy + 10, alpha(step.routeColor, 1f));
				sink.textLeft(font, fit(step.routeName + " " + s("开往 ", "to ")
						+ (step.terminalId == null ? "" : stationLabel(model.station(step.terminalId))), w - 22),
						x + 12, cy, textColor(), false);
				MapModel.Station to = model.station(step.stations.get(step.stations.size() - 1));
				sink.textLeft(font, fit((step.stations.size() - 1) + s(" 站 → ", " stops -> ")
						+ (to == null ? "" : stationLabel(to)), w - 22), x + 12, cy + 9, dimColor(), false);
			}
			cy += STEP_H;
		}
	}

	/** 路径面板的顶部 y：紧跟在详情面板下面 */
	private int routePanelY() {
		return 8 + (detailPanelH > 0 ? detailPanelH + 6 : 0);
	}

	/** 当前方案实际画出来的步数（与 drawRoutePanel 的封顶保持一致） */
	private int visibleStepCount() {
		if (routeOptions.isEmpty()) {
			return 0;
		}
		RoutePlanner.Option option = routeOptions.get(Math.min(activeRouteTab, routeOptions.size() - 1));
		return Math.min(option.steps.size(), 7);
	}

	// ===== 行程记录弹窗 =====

	private static final int TRIPS_W = 330;
	private static final int TRIPS_ROW_H = 32;

	private int tripsRows() {
		return Math.min(6, trips.size());
	}

	private int tripsDialogHeight() {
		int body = tripsLoading || trips.size() == 0 ? 24 : tripsRows() * TRIPS_ROW_H;
		return 26 + body + 26;
	}

	private int tripsDialogY() {
		return Math.max(8, (height - tripsDialogHeight()) / 2);
	}

	private void drawTripsDialog(GuiSink sink) {
		if (!tripsOpen) {
			return;
		}
		sink.dim(width, height, 0x99000000);
		int x = (width - TRIPS_W) / 2;
		int y = tripsDialogY();
		int h = tripsDialogHeight();
		panel(sink, x, y, TRIPS_W, h);
		sink.textLeft(font, s("行程记录", "Trip records"), x + 8, y + 8, textColor(), false);
		sink.textLeft(font, "×", x + TRIPS_W - 14, y + 8, dimColor(), false);
		int cy = y + 26;
		if (tripsLoading) {
			sink.textLeft(font, s("正在加载...", "Loading..."), x + 8, cy + 4, dimColor(), false);
		} else if (trips.size() == 0) {
			sink.textLeft(font, s("还没有行程记录", "No trips yet"), x + 8, cy + 4, dimColor(), false);
		} else {
			for (int i = 0; i < tripsRows(); i++) {
				JsonObject trip = trips.get(i).getAsJsonObject();
				int ry = cy + i * TRIPS_ROW_H;
				if (i % 2 == 1) {
					sink.fill(x + 2, ry, x + TRIPS_W - 2, ry + TRIPS_ROW_H - 2, dark ? 0x33000000 : 0x11000000);
				}
				String status = "completed".equals(str(trip, "status"))
						? s("已完成", "Completed") : s("已停止", "Stopped");
				String title = status + "  " + tripPointName(trip, "from") + " → " + tripPointName(trip, "to");
				sink.textLeft(font, fit(title, TRIPS_W - 104), x + 8, ry + 3, textColor(), false);
				String meta = fmtDist(num(trip, "dist")) + s(" · 约", " · ~") + fmtMinutes(num(trip, "timeSec"))
						+ s(" · 换乘 ", " · transfers ") + (int) num(trip, "transfers");
				sink.textLeft(font, fit(meta, TRIPS_W - 104), x + 8, ry + 16, dimColor(), false);
				// 票根（只保存，不打印）
				sink.fill(x + TRIPS_W - 86, ry + 6, x + TRIPS_W - 48, ry + 24, dark ? 0xFF2A3A4A : 0xFFD6E4F0);
				sink.text(font, s("票根", "Ticket"), x + TRIPS_W - 67f, ry + 11, textColor(), false);
				sink.fill(x + TRIPS_W - 44, ry + 6, x + TRIPS_W - 6, ry + 24, dark ? 0xFF4A2A2A : 0xFFF0D6D6);
				sink.text(font, s("删除", "Del"), x + TRIPS_W - 25f, ry + 11, textColor(), false);
			}
		}
		int by = y + h - 22;
		sink.fill(x + TRIPS_W / 2 - 40, by, x + TRIPS_W / 2 + 40, by + 16, dark ? 0xFF2A2A3C : 0xFFE4E4EC);
		border(sink, x + TRIPS_W / 2 - 40, by, 80, 16, dark ? 0xFF3A3A4A : 0xFFBBBBCC);
		sink.text(font, s("关闭", "Close"), x + TRIPS_W / 2f, by + 4, textColor(), false);
	}

	/** 行程记录弹窗的点击；返回 true 表示已处理（模态，不外传） */
	private boolean tripsDialogClick(double mouseX, double mouseY) {
		if (!tripsOpen) {
			return false;
		}
		int x = (width - TRIPS_W) / 2;
		int y = tripsDialogY();
		int h = tripsDialogHeight();
		if (mouseX >= x + TRIPS_W - 24 && mouseX <= x + TRIPS_W && mouseY >= y && mouseY <= y + 22) {
			tripsOpen = false;
			return true;
		}
		int by = y + h - 22;
		if (mouseY >= by && mouseY <= by + 16 && Math.abs(mouseX - width / 2.0) <= 40) {
			tripsOpen = false;
			return true;
		}
		if (!tripsLoading && mouseY >= y + 26 && mouseY <= y + 26 + tripsRows() * TRIPS_ROW_H) {
			int cy = y + 26;
			for (int i = 0; i < tripsRows(); i++) {
				int ry = cy + i * TRIPS_ROW_H;
				if (mouseY < ry + 6 || mouseY > ry + 24) {
					continue;
				}
				JsonObject trip = trips.get(i).getAsJsonObject();
				if (mouseX >= x + TRIPS_W - 86 && mouseX <= x + TRIPS_W - 48) {
					saveTicket(trip);
					return true;
				}
				if (mouseX >= x + TRIPS_W - 44 && mouseX <= x + TRIPS_W - 6) {
					deleteTrip(str(trip, "id"));
					return true;
				}
			}
		}
		// 模态：点空白处也不穿透到地图
		return true;
	}

	/** 生成纪念票根并直接保存（不做打印） */
	private void saveTicket(JsonObject trip) {
		try {
			File file = MapExporter.saveTicket(trip, english);
			if (file == null) {
				showToast(s("票根保存失败", "Failed to save ticket"), 0xFFFF8080);
			} else {
				showToast(s("票根已保存到 ", "Ticket saved to ") + relative(file), 0xFF9BE59B);
			}
		} catch (Throwable t) {
			showToast(s("票根保存失败", "Failed to save ticket"), 0xFFFF8080);
		}
	}

	private void loadTrips() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			trips = new JsonArray();
			tripsLoading = false;
			return;
		}
		final String uuid = mc.player.getUUID().toString();
		tripsLoading = true;
		Thread thread = new Thread(() -> {
			JsonArray array = fetchTrips(uuid);
			Minecraft.getInstance().execute(() -> {
				trips = array;
				tripsLoading = false;
			});
		}, "MTRMap-Trips");
		thread.setDaemon(true);
		thread.start();
	}

	private void deleteTrip(final String id) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || id == null || id.isEmpty()) {
			return;
		}
		final String uuid = mc.player.getUUID().toString();
		tripsLoading = true;
		Thread thread = new Thread(() -> {
			JsonObject body = new JsonObject();
			body.addProperty("uuid", uuid);
			body.addProperty("id", id);
			MapDataClient.postSync("/api/trips/delete", body);
			JsonArray array = fetchTrips(uuid);
			Minecraft.getInstance().execute(() -> {
				trips = array;
				tripsLoading = false;
			});
		}, "MTRMap-Trips");
		thread.setDaemon(true);
		thread.start();
	}

	private static JsonArray fetchTrips(String uuid) {
		JsonElement result = MapDataClient.getWithParam("/api/trips", "uuid", uuid);
		return result != null && result.isJsonArray() ? result.getAsJsonArray() : new JsonArray();
	}

	private static String tripPointName(JsonObject trip, String key) {
		if (!trip.has(key) || !trip.get(key).isJsonObject()) {
			return "-";
		}
		return str(trip.getAsJsonObject(key), "name");
	}

	private static String str(JsonObject obj, String key) {
		JsonElement element = obj.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	private static double num(JsonObject obj, String key) {
		JsonElement element = obj.get(key);
		try {
			return element == null || element.isJsonNull() ? 0 : element.getAsDouble();
		} catch (Exception e) {
			return 0;
		}
	}

	// ===== 导出图片弹窗 =====

	private static final int EXPORT_W = 240;

	private int exportDialogHeight() {
		return 26 + 26 + ("jpg".equals(exportFormat) ? 24 : 0) + 26;
	}

	private int exportDialogY() {
		return Math.max(8, (height - exportDialogHeight()) / 2);
	}

	private int exportFormatButtonX(int index) {
		int bw = 60;
		int gap = 6;
		return (width - EXPORT_W) / 2 + (EXPORT_W - (2 * bw + gap)) / 2 + index * (bw + gap);
	}

	private void drawExportDialog(GuiSink sink) {
		if (!exportOpen) {
			return;
		}
		sink.dim(width, height, 0x99000000);
		int x = (width - EXPORT_W) / 2;
		int y = exportDialogY();
		int h = exportDialogHeight();
		panel(sink, x, y, EXPORT_W, h);
		sink.textLeft(font, s("导出图片", "Export image"), x + 8, y + 8, textColor(), false);
		sink.textLeft(font, "×", x + EXPORT_W - 14, y + 8, dimColor(), false);

		int by = y + 26;
		String[] formats = {"png", "jpg"};
		String[] labels = {"PNG", "JPG"};
		for (int i = 0; i < formats.length; i++) {
			boolean active = formats[i].equals(exportFormat);
			int bx = exportFormatButtonX(i);
			sink.fill(bx, by, bx + 60, by + 20, active ? 0xFF3B5180 : (dark ? 0xFF2A2A3C : 0xFFE4E4EC));
			border(sink, bx, by, 60, 20, dark ? 0xFF3A3A4A : 0xFFBBBBCC);
			sink.text(font, labels[i], bx + 30f, by + 6, textColor(), false);
		}

		if ("jpg".equals(exportFormat)) {
			int qy = y + 52;
			sink.textLeft(font, s("质量:", "Quality:"), x + 8, qy + 3, dimColor(), false);
			int trackX = x + 54;
			int trackW = 122;
			sink.fill(trackX, qy + 6, trackX + trackW, qy + 10, dark ? 0xFF2A2A3C : 0xFFD8D8E0);
			int filled = (int) Math.round(trackW * (exportQuality - 1) / 99.0);
			sink.fill(trackX, qy + 6, trackX + filled, qy + 10, 0xFF3B6FD4);
			sink.fill(trackX + filled - 1, qy + 3, trackX + filled + 1, qy + 13, 0xFFFFFFFF);
			sink.textLeft(font, exportQuality + "%", trackX + trackW + 6, qy + 3, textColor(), false);
		}

		int cy = y + h - 24;
		int cancelX = x + EXPORT_W / 2 - 40;
		sink.fill(cancelX, cy, cancelX + 80, cy + 18, dark ? 0xFF2A2A3C : 0xFFE4E4EC);
		border(sink, cancelX, cy, 80, 18, dark ? 0xFF3A3A4A : 0xFFBBBBCC);
		sink.text(font, s("取消", "Cancel"), cancelX + 40f, cy + 5, textColor(), false);
	}

	/** 导出弹窗的点击；返回 true 表示已处理（模态） */
	private boolean exportDialogClick(double mouseX, double mouseY) {
		if (!exportOpen) {
			return false;
		}
		int x = (width - EXPORT_W) / 2;
		int y = exportDialogY();
		int h = exportDialogHeight();
		if (mouseX >= x + EXPORT_W - 24 && mouseX <= x + EXPORT_W && mouseY >= y && mouseY <= y + 22) {
			exportOpen = false;
			return true;
		}
		int by = y + 26;
		if (mouseY >= by && mouseY <= by + 20) {
			for (int i = 0; i < 2; i++) {
				int bx = exportFormatButtonX(i);
				if (mouseX >= bx && mouseX <= bx + 60) {
					exportFormat = i == 0 ? "png" : "jpg";
					// 与网页一致：选好格式就直接开始导出
					startExport();
					return true;
				}
			}
		}
		if ("jpg".equals(exportFormat)) {
			int qy = y + 52;
			int trackX = x + 54;
			int trackW = 122;
			if (mouseX >= trackX - 4 && mouseX <= trackX + trackW + 4 && mouseY >= qy && mouseY <= qy + 16) {
				qualityDragging = true;
				updateQuality(mouseX, trackX, trackW);
				return true;
			}
		}
		int cy = y + h - 24;
		int cancelX = x + EXPORT_W / 2 - 40;
		if (mouseY >= cy && mouseY <= cy + 18 && mouseX >= cancelX && mouseX <= cancelX + 80) {
			exportOpen = false;
			return true;
		}
		// 模态：点空白处不穿透
		return true;
	}

	private void updateQuality(double mouseX, int trackX, int trackW) {
		double ratio = (mouseX - trackX) / (double) trackW;
		exportQuality = (int) Math.round(1 + Math.max(0, Math.min(1, ratio)) * 99);
	}

	/** 在后台线程导出（要下瓦片 + 编码，不能卡住渲染线程） */
	private void startExport() {
		if (exporting) {
			return;
		}
		final MapModel model = MapDataClient.model();
		final boolean depots = showDepots;
		final boolean darkMode = dark;
		final boolean en = english;
		final String format = exportFormat;
		final int quality = exportQuality;
		exporting = true;
		exportOpen = false;
		Thread thread = new Thread(() -> {
			File file = MapExporter.exportMap(model, depots, darkMode, en, format, quality);
			Minecraft.getInstance().execute(() -> {
				exporting = false;
				if (file == null) {
					showToast(s("导出失败：没有可导出的数据", "Export failed: no data"), 0xFFFF8080);
				} else {
					showToast(s("已保存到 ", "Saved to ") + relative(file), 0xFF9BE59B);
				}
			});
		}, "MTRMap-Export");
		thread.setDaemon(true);
		thread.start();
	}

	// ===== 提示条 =====

	private void showToast(String text, int color) {
		toastText = text;
		toastColor = color;
		toastUntil = System.currentTimeMillis() + 4000L;
	}

	private void drawToast(GuiSink sink) {
		if (toastText == null) {
			return;
		}
		if (System.currentTimeMillis() > toastUntil) {
			toastText = null;
			return;
		}
		int w = font.width(toastText) + 20;
		int x = (width - w) / 2;
		int y = height - 36;
		sink.fill(x, y, x + w, y + 20, 0xE6000000);
		border(sink, x, y, w, 20, toastColor);
		sink.text(font, toastText, x + w / 2f, y + 6, 0xFFFFFFFF, false);
	}

	/** 把绝对路径缩成「mtrmap/xxx.png」，提示里更短更清楚 */
	private static String relative(File file) {
		return "mtrmap/" + file.getName();
	}

	// ===== 选站 / 搜索 =====

	private MapModel.Station pickStation(MapModel model, double mouseX, double mouseY, double radius) {
		double pick = Math.max(8, radius + 3);
		MapModel.Station best = null;
		double bestDist = pick;
		for (MapModel.Station station : model.stations) {
			double d = Math.hypot(worldToScreenX(station.x) - mouseX, worldToScreenY(station.z) - mouseY);
			if (d < bestDist) {
				bestDist = d;
				best = station;
			}
		}
		return best;
	}

	private void handleStationPick(MapModel.Station station) {
		if (!routeMode) {
			detailStation = station;
			detailRoute = null;
			return;
		}
		if (routeStartId < 0) {
			routeStartId = station.id;
			routeFromMyLocation = false;
		} else if (routeEndId < 0) {
			if (station.id == routeStartId) {
				return;
			}
			routeEndId = station.id;
			computeRoutes();
		} else {
			routeStartId = station.id;
			routeEndId = -1;
			routeOptions = new ArrayList<>();
			routeMessage = null;
			startWalk = null;
			routeFromMyLocation = false;
		}
	}

	private void updateSearchHits(MapModel model) {
		searchHits.clear();
		String query = searchText.trim().toLowerCase();
		if (query.isEmpty()) {
			searchIndex = 0;
			return;
		}
		if (searchRouteMode) {
			for (MapModel.Route route : model.routes) {
				String[] n = MapModel.splitName(route.name);
				if (matches(n[0], query) || matches(n[1], query)) {
					searchHits.add(route);
				}
			}
		} else {
			for (MapModel.Station station : model.stations) {
				String[] n = MapModel.splitName(station.name);
				if (matches(n[0], query) || matches(n[1], query)) {
					searchHits.add(station);
				}
			}
		}
		if (searchHits.size() > 40) {
			searchHits.subList(40, searchHits.size()).clear();
		}
		searchIndex = Math.max(0, Math.min(searchIndex, searchHits.size() - 1));
	}

	private static boolean matches(String value, String query) {
		return value != null && !value.isEmpty() && value.toLowerCase().contains(query);
	}

	private String hitLabel(Object hit) {
		if (hit instanceof MapModel.Station) {
			MapModel.Station station = (MapModel.Station) hit;
			String[] n = MapModel.splitName(station.name);
			return english && !n[1].isEmpty() ? n[1] : n[0];
		}
		MapModel.Route route = (MapModel.Route) hit;
		String[] n = MapModel.splitName(route.name);
		return (english && !n[1].isEmpty() ? n[1] : n[0]) + "  (" + route.stations.size() + ")";
	}

	private void applySearchSelection() {
		if (searchIndex < 0 || searchIndex >= searchHits.size()) {
			return;
		}
		Object hit = searchHits.get(searchIndex);
		if (hit instanceof MapModel.Station) {
			MapModel.Station station = (MapModel.Station) hit;
			detailStation = station;
			detailRoute = null;
			centerOn(station.x, station.z);
		} else {
			MapModel.Route route = (MapModel.Route) hit;
			detailRoute = route;
			detailStation = null;
			focusRoute(route);
		}
		searchFocused = false;
	}

	private void centerOn(double x, double z) {
		centerX = x;
		centerZ = z;
	}

	private void focusRoute(MapModel.Route route) {
		MapModel model = MapDataClient.model();
		double minX = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		boolean any = false;
		for (Long id : route.stations) {
			MapModel.Station station = model.station(id);
			if (station == null) {
				continue;
			}
			minX = Math.min(minX, station.x);
			maxX = Math.max(maxX, station.x);
			minZ = Math.min(minZ, station.z);
			maxZ = Math.max(maxZ, station.z);
			any = true;
		}
		if (!any) {
			return;
		}
		centerX = (minX + maxX) / 2;
		centerZ = (minZ + maxZ) / 2;
		double w = Math.max(1, maxX - minX);
		double h = Math.max(1, maxZ - minZ);
		double fit = Math.min((width - 160.0) / w, (height - 160.0) / h);
		scale = clamp(fit, MIN_SCALE, MAX_SCALE);
	}

	// ===== 路径查询与导航下发 =====

	private void computeRoutes() {
		MapModel model = MapDataClient.model();
		routeMessage = null;
		routeOptions = new ArrayList<>();
		activeRouteTab = 0;
		startWalk = null;
		if (routeStartId < 0 || routeEndId < 0) {
			return;
		}
		if (routeStartId == routeEndId) {
			routeMessage = s("起点与终点相同", "Same station");
			return;
		}
		routeOptions = RoutePlanner.compute(model, routeStartId, routeEndId);
		if (routeOptions.isEmpty()) {
			routeMessage = s("无法到达", "Unreachable");
			return;
		}
		updateStartWalk();
		pushNavTask();
	}

	private void updateStartWalk() {
		if (routeFromMyLocation && hasMyLocation && routeStartId >= 0) {
			startWalk = RoutePlanner.startWalk(MapDataClient.model(), myX, myZ, routeStartId);
		} else {
			startWalk = null;
		}
	}

	/** 把当前方案同步到游戏内导航（走 /api/nav，客户端 NavController 轮询领取） */
	private void pushNavTask() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || routeOptions.isEmpty() || routeStartId < 0 || routeEndId < 0) {
			return;
		}
		MapModel model = MapDataClient.model();
		RoutePlanner.Option option = routeOptions.get(Math.min(activeRouteTab, routeOptions.size() - 1));
		MapModel.Station start = model.station(routeStartId);
		MapModel.Station end = model.station(routeEndId);
		if (start == null || end == null) {
			return;
		}
		StringBuilder rev = new StringBuilder();
		rev.append(routeStartId).append('|').append(routeEndId).append('|').append(option.mode.name()).append('|');
		for (RoutePlanner.Step step : option.steps) {
			if (step.walk) {
				rev.append("w").append(step.toStation);
			} else {
				rev.append("r").append(step.routeName).append(':')
						.append(step.stations.get(0)).append('>')
						.append(step.stations.get(step.stations.size() - 1));
			}
			rev.append(',');
		}
		JsonObject task = new JsonObject();
		task.addProperty("rev", rev.toString());
		task.addProperty("startedAt", System.currentTimeMillis() / 1000L);
		task.add("from", point(stationLabel(start), start.x, start.z));
		task.add("to", point(stationLabel(end), end.x, end.z));
		task.addProperty("dist", Math.round(option.dist));
		task.addProperty("timeSec", Math.round(option.timeSec));
		task.addProperty("transfers", option.transfers);
		if (startWalk != null && hasMyLocation) {
			JsonObject walk = new JsonObject();
			walk.addProperty("dir", startWalk.dirIndex == null ? "" : RoutePlanner.DIRECTIONS[startWalk.dirIndex]);
			walk.addProperty("distance", Math.round(startWalk.dist));
			walk.addProperty("x", myX);
			walk.addProperty("z", myZ);
			walk.addProperty("station", stationLabel(start));
			task.add("startWalk", walk);
		} else {
			task.add("startWalk", null);
		}
		JsonArray steps = new JsonArray();
		for (RoutePlanner.Step step : option.steps) {
			JsonObject obj = new JsonObject();
			if (step.walk) {
				MapModel.Station to = model.station(step.toStation);
				obj.addProperty("type", "walk");
				obj.addProperty("to", to == null ? "" : stationLabel(to));
				obj.addProperty("distance", Math.round(step.dist));
				obj.addProperty("dir", step.dirIndex == null ? "" : RoutePlanner.DIRECTIONS[step.dirIndex]);
				obj.addProperty("x", to == null ? 0 : to.x);
				obj.addProperty("z", to == null ? 0 : to.z);
			} else {
				MapModel.Station to = model.station(step.stations.get(step.stations.size() - 1));
				MapModel.Station from = model.station(step.stations.get(0));
				obj.addProperty("type", "ride");
				obj.addProperty("line", routeLabel(step.routeName));
				obj.addProperty("color", step.routeColor);
				obj.addProperty("towards", step.terminalId == null ? ""
						: stationLabel(model.station(step.terminalId)));
				obj.addProperty("rideCount", step.stations.size() - 1);
				obj.addProperty("from", from == null ? "" : stationLabel(from));
				obj.addProperty("to", to == null ? "" : stationLabel(to));
				obj.addProperty("x", to == null ? 0 : to.x);
				obj.addProperty("z", to == null ? 0 : to.z);
			}
			steps.add(obj);
		}
		task.add("steps", steps);

		lastNavRev = rev.toString();
		JsonObject body = new JsonObject();
		body.addProperty("uuid", mc.player.getUUID().toString());
		body.add("task", task);
		MapDataClient.postAsync("/api/nav", body);
	}

	private static JsonObject point(String name, double x, double z) {
		JsonObject obj = new JsonObject();
		obj.addProperty("name", name);
		obj.addProperty("x", x);
		obj.addProperty("z", z);
		return obj;
	}

	private void useMyLocation() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			routeMessage = s("您未在游戏中", "Not in game");
			return;
		}
		myX = mc.player.getX();
		myZ = mc.player.getZ();
		hasMyLocation = true;
		MapModel.Station nearest = RoutePlanner.nearestStation(MapDataClient.model(), myX, myZ);
		if (nearest == null) {
			routeMessage = s("附近没有车站", "No station nearby");
			return;
		}
		routeStartId = nearest.id;
		routeFromMyLocation = true;
		if (routeEndId >= 0) {
			computeRoutes();
		} else {
			updateStartWalk();
		}
		centerOn(myX, myZ);
	}

	// ===== 输入 =====

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 导出 / 行程记录都是模态弹窗：开着的时候所有点击都只给它
		if (exportOpen) {
			exportDialogClick(mouseX, mouseY);
			return true;
		}
		if (tripsOpen) {
			tripsDialogClick(mouseX, mouseY);
			return true;
		}
		if (button == 0) {
			// 工具栏
			for (Btn tool : toolbarButtons()) {
				if (tool.contains(mouseX, mouseY)) {
					onToolbar(tool.id);
					return true;
				}
			}
			// 详情面板关闭按钮
			if ((detailStation != null || detailRoute != null)
					&& detailCloseHit(mouseX, mouseY)) {
				detailStation = null;
				detailRoute = null;
				return true;
			}
			// 路径面板
			if (routeMode && routePanelHit(mouseX, mouseY)) {
				return true;
			}
			// 搜索框
			int x = 8;
			int y = 8;
			int w = leftStackWidth();
			int typeW = 44;
			if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 20) {
				if (mouseX <= x + 2 + typeW) {
					searchRouteMode = !searchRouteMode;
				} else {
					searchFocused = true;
					searchCursor = searchText.length();
				}
				return true;
			}
			// 候选词
			int resultsY = 30;
			if (searchFocused && !searchHits.isEmpty()) {
				int rows = Math.min(8, searchHits.size());
				if (mouseX >= x && mouseX <= x + w && mouseY >= resultsY && mouseY < resultsY + rows * ROW) {
					searchIndex = (int) ((mouseY - resultsY) / ROW);
					applySearchSelection();
					return true;
				}
			}
			searchFocused = false;

			// 落在左侧堆叠（状态 / 图例 / 线路一览）里：只是看面板，不穿透去选站
			if (mouseX <= leftStackRight && mouseY <= leftStackBottom) {
				return true;
			}

			// 点地图：选站
			MapModel model = MapDataClient.model();
			double radius = Math.max(6, 8 * Math.sqrt(scale));
			MapModel.Station station = pickStation(model, mouseX, mouseY, radius);
			if (station != null) {
				handleStationPick(station);
				return true;
			}
			dragging = true;
			dragMoved = false;
			dragLastX = mouseX;
			dragLastY = mouseY;
			return true;
		}
		if (button == 1) {
			detailStation = null;
			detailRoute = null;
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		dragging = false;
		qualityDragging = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		// 拖质量滑块时不要同时平移地图
		if (qualityDragging && exportOpen) {
			int x = (width - EXPORT_W) / 2;
			updateQuality(mouseX, x + 54, 122);
			return true;
		}
		if (dragging) {
			centerX -= (mouseX - dragLastX) / scale;
			centerZ -= (mouseY - dragLastY) / scale;
			dragLastX = mouseX;
			dragLastY = mouseY;
			dragMoved = true;
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	//? if >=1.21.1 {
	/*@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return onScroll(mouseX, mouseY, scrollY);
	}
	*///?} else {
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return onScroll(mouseX, mouseY, delta);
	}
	//?}

	private boolean onScroll(double mouseX, double mouseY, double delta) {
		if (delta == 0) {
			return false;
		}
		zoomAt(mouseX, mouseY, Math.pow(1.25, delta));
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (exportOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
			exportOpen = false;
			return true;
		}
		if (tripsOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
			tripsOpen = false;
			return true;
		}
		if (searchFocused) {
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				searchFocused = false;
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				applySearchSelection();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_BACKSPACE && searchCursor > 0) {
				searchText = searchText.substring(0, searchCursor - 1) + searchText.substring(searchCursor);
				searchCursor--;
				searchIndex = 0;
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_DELETE && searchCursor < searchText.length()) {
				searchText = searchText.substring(0, searchCursor) + searchText.substring(searchCursor + 1);
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_LEFT && searchCursor > 0) {
				searchCursor--;
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_RIGHT && searchCursor < searchText.length()) {
				searchCursor++;
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_DOWN && !searchHits.isEmpty()) {
				searchIndex = Math.min(searchIndex + 1, searchHits.size() - 1);
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_UP && !searchHits.isEmpty()) {
				searchIndex = Math.max(searchIndex - 1, 0);
				return true;
			}
			if (isPasteCombo(keyCode)) {
				String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
				if (clipboard != null && !clipboard.isEmpty()) {
					searchText = searchText.substring(0, searchCursor) + clipboard + searchText.substring(searchCursor);
					searchCursor += clipboard.length();
				}
				return true;
			}
			// 焦点在搜索框时吞掉其它可见字符键，避免触发原版快捷键
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private boolean isPasteCombo(int keyCode) {
		boolean ctrl = InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
				|| InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
		return ctrl && (keyCode == GLFW.GLFW_KEY_V);
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		if (searchFocused && codePoint >= 32 && codePoint != 127) {
			searchText = searchText.substring(0, searchCursor) + codePoint + searchText.substring(searchCursor);
			searchCursor++;
			searchIndex = 0;
			return true;
		}
		return super.charTyped(codePoint, modifiers);
	}

	// ===== 点击分区 =====

	private boolean detailCloseHit(double mouseX, double mouseY) {
		int w = PANEL_W;
		int x = width - 8 - TOOL_W - 6 - w;
		return mouseX >= x && mouseX <= x + w && mouseY >= 8 && mouseY <= 8 + 15;
	}

	private boolean routePanelHit(double mouseX, double mouseY) {
		int w = PANEL_W;
		int x = width - 8 - TOOL_W - 6 - w;
		int y = routePanelY();
		if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + routePanelHeight()) {
			return false;
		}
		// 关闭
		if (mouseY <= y + 16 && mouseX >= x + w - 20) {
			setRouteMode(false);
			return true;
		}
		// 按钮行
		int by = y + 29;
		if (mouseY >= by && mouseY <= by + 13) {
			int bw = (w - 15) / 3;
			int index = (int) ((mouseX - x - 6) / (bw + 3));
			if (index >= 0 && index < 3) {
				if (index == 0) {
					computeRoutes();
				} else if (index == 1) {
					routeStartId = -1;
					routeEndId = -1;
					routeOptions = new ArrayList<>();
					routeMessage = null;
					startWalk = null;
					routeFromMyLocation = false;
				} else {
					useMyLocation();
				}
			}
			return true;
		}
		// 方案标签页
		if (!routeOptions.isEmpty()) {
			int tabsY = by + 18 + panelLinesHeight();
			if (mouseY >= tabsY && mouseY <= tabsY + 11) {
				int tabW = (w - 12) / routeOptions.size();
				int index = (int) ((mouseX - x - 6) / tabW);
				if (index >= 0 && index < routeOptions.size()) {
					activeRouteTab = index;
					updateStartWalk();
					pushNavTask();
				}
				return true;
			}
		}
		return true;
	}

	/** 路径面板顶部那几行提示占的高度 */
	private int panelLinesHeight() {
		int lines = 0;
		if (startWalk != null) {
			lines++;
		}
		if (routeMessage != null) {
			lines++;
		}
		return lines * LINE_H;
	}

	/** 路径面板高度（画与点击必须用同一个值） */
	private int routePanelHeight() {
		if (routeOptions.isEmpty()) {
			return 53 + panelLinesHeight();
		}
		return 74 + panelLinesHeight() + visibleStepCount() * STEP_H;
	}

	private void onToolbar(String id) {
		switch (id) {
			case "route":
				setRouteMode(!routeMode);
				break;
			case "theme":
				dark = !dark;
				break;
			case "lang":
				english = !english;
				break;
			case "depot":
				showDepots = !showDepots;
				break;
			case "zoomIn":
				zoomAt(width / 2.0, height / 2.0, 1.25);
				break;
			case "zoomOut":
				zoomAt(width / 2.0, height / 2.0, 0.8);
				break;
			case "reset":
				viewInitialized = true;
				fitView();
				break;
			case "my":
				setRouteMode(true);
				useMyLocation();
				break;
			case "export":
				exportOpen = true;
				break;
			case "trips":
				tripsOpen = !tripsOpen;
				if (tripsOpen) {
					loadTrips();
				}
				break;
			case "close":
				onClose();
				break;
			default:
				break;
		}
	}

	private void setRouteMode(boolean on) {
		routeMode = on;
		if (!on) {
			routeStartId = -1;
			routeEndId = -1;
			routeOptions = new ArrayList<>();
			routeMessage = null;
			startWalk = null;
			routeFromMyLocation = false;
		}
	}

	// ===== 绘制小工具 =====

	private void panel(GuiSink sink, int x, int y, int w, int h) {
		sink.fill(x, y, x + w, y + h, dark ? 0xE61E1E2E : 0xF2FFFFFF);
		border(sink, x, y, w, h, dark ? 0xFF3A3A4A : 0xFFBBBBCC);
	}

	private void border(GuiSink sink, int x, int y, int w, int h, int color) {
		sink.fill(x, y, x + w, y + 1, color);
		sink.fill(x, y + h - 1, x + w, y + h, color);
		sink.fill(x, y, x + 1, y + h, color);
		sink.fill(x + w - 1, y, x + w, y + h, color);
	}

	/** 一条粗线段：按法线方向张开成一个四边形，一次画完（不是逐像素填） */
	private void line(GuiSink sink, double x1, double y1, double x2, double y2, int color, int thickness) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		double len = Math.hypot(dx, dy);
		double half = Math.max(0.5, thickness / 2.0);
		if (len < 0.0001) {
			sink.fill((int) Math.round(x1 - half), (int) Math.round(y1 - half),
					(int) Math.round(x1 + half), (int) Math.round(y1 + half), color);
			return;
		}
		double nx = -dy / len * half;
		double ny = dx / len * half;
		sink.quad((float) (x1 + nx), (float) (y1 + ny),
				(float) (x2 + nx), (float) (y2 + ny),
				(float) (x2 - nx), (float) (y2 - ny),
				(float) (x1 - nx), (float) (y1 - ny), color);
	}

	private void drawPolyline(GuiSink sink, List<double[]> points, int color, int thickness) {
		for (int i = 0; i < points.size() - 1; i++) {
			line(sink, worldToScreenX(points.get(i)[0]), worldToScreenY(points.get(i)[1]),
					worldToScreenX(points.get(i + 1)[0]), worldToScreenY(points.get(i + 1)[1]), color, thickness);
		}
	}

	private void disc(GuiSink sink, double cx, double cy, double r, int fill, int outline, int thickness) {
		int left = (int) Math.round(cx - r);
		int top = (int) Math.round(cy - r);
		int size = (int) Math.round(r * 2);
		fillCircle(sink, left, top, size, fill);
		strokeCircle(sink, left, top, size, outline, thickness);
	}

	private void fillCircle(GuiSink sink, int left, int top, int size, int color) {
		if ((color >>> 24) == 0) {
			return;
		}
		for (int y = 0; y < size; y++) {
			double dy = y - size / 2.0 + 0.5;
			double half = Math.sqrt(Math.max(0, (size / 2.0) * (size / 2.0) - dy * dy));
			int x0 = (int) Math.round(left + size / 2.0 - half);
			int x1 = (int) Math.round(left + size / 2.0 + half);
			sink.fill(x0, top + y, x1, top + y + 1, color);
		}
	}

	private void strokeCircle(GuiSink sink, int left, int top, int size, int color, int thickness) {
		int segments = size < 12 ? 12 : 24;
		double cx = left + size / 2.0;
		double cy = top + size / 2.0;
		double r = size / 2.0;
		for (int i = 0; i < segments; i++) {
			double a0 = i * 2 * Math.PI / segments;
			double a1 = (i + 1) * 2 * Math.PI / segments;
			line(sink, cx + Math.cos(a0) * r, cy + Math.sin(a0) * r,
					cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, color, thickness);
		}
	}

	/** 胶囊形换乘站标记：{cx, cy, r, half, vertical} */
	private void drawCapsule(GuiSink sink, double[] capsule, int fill, int outline, int thickness) {
		double cx = capsule[0];
		double cy = capsule[1];
		double r = capsule[2];
		double half = capsule[3];
		boolean vertical = capsule[4] > 0;
		// 两端圆 + 中间矩形
		if (vertical) {
			sink.fill((int) (cx - r), (int) (cy - half), (int) (cx + r), (int) (cy + half), fill);
			fillCircle(sink, (int) Math.round(cx - r), (int) Math.round(cy - half - r), (int) Math.round(r * 2), fill);
			fillCircle(sink, (int) Math.round(cx - r), (int) Math.round(cy + half - r), (int) Math.round(r * 2), fill);
			strokeCircle(sink, (int) Math.round(cx - r), (int) Math.round(cy - half - r), (int) Math.round(r * 2), outline, thickness);
			strokeCircle(sink, (int) Math.round(cx - r), (int) Math.round(cy + half - r), (int) Math.round(r * 2), outline, thickness);
			sink.fill((int) (cx - r), (int) (cy - half), (int) (cx - r + 1), (int) (cy + half), outline);
			sink.fill((int) (cx + r - 1), (int) (cy - half), (int) (cx + r), (int) (cy + half), outline);
		} else {
			sink.fill((int) (cx - half), (int) (cy - r), (int) (cx + half), (int) (cy + r), fill);
			fillCircle(sink, (int) Math.round(cx - half - r), (int) Math.round(cy - r), (int) Math.round(r * 2), fill);
			fillCircle(sink, (int) Math.round(cx + half - r), (int) Math.round(cy - r), (int) Math.round(r * 2), fill);
			strokeCircle(sink, (int) Math.round(cx - half - r), (int) Math.round(cy - r), (int) Math.round(r * 2), outline, thickness);
			strokeCircle(sink, (int) Math.round(cx + half - r), (int) Math.round(cy - r), (int) Math.round(r * 2), outline, thickness);
			sink.fill((int) (cx - half), (int) (cy - r), (int) (cx + half), (int) (cy - r + 1), outline);
			sink.fill((int) (cx - half), (int) (cy + r - 1), (int) (cx + half), (int) (cy + r), outline);
		}
	}

	private void small(GuiSink sink, String text, float x, float y, int color) {
		sink.push();
		sink.translate(x, y, 0f);
		sink.scale(0.7f);
		sink.textLeft(font, text, 0f, 0f, color, false);
		sink.pop();
	}

	private void drawTooltip(GuiSink sink, String text, int x, int y) {
		int w = font.width(text) + 8;
		int h = 14;
		x = Math.max(2, Math.min(x, width - w - 2));
		y = Math.max(2, Math.min(y, height - h - 2));
		sink.fill(x, y, x + w, y + h, 0xF0101018);
		border(sink, x, y, w, h, 0xFF7EC8E3);
		sink.textLeft(font, text, x + 4, y + 3, 0xFFFFFFFF, false);
	}

	private String fit(String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			String next = sb.toString() + text.charAt(i);
			if (font.width(next + "…") > maxWidth) {
				break;
			}
			sb.append(text.charAt(i));
		}
		return sb + "…";
	}

	private int textColor() {
		return dark ? 0xFFECEFF4 : 0xFF222233;
	}

	private int dimColor() {
		return dark ? 0xFFB0B0BE : 0xFF666677;
	}

	private int alpha(int argb, float a) {
		int base = (argb >>> 24) & 0xFF;
		int na = Math.round((base == 0 ? 255 : base) * clamp(a, 0f, 1f));
		return (Math.min(255, na) << 24) | (argb & 0x00FFFFFF);
	}

	private static double clamp(double v, double min, double max) {
		return v < min ? min : (v > max ? max : v);
	}

	private static float clamp(float v, float min, float max) {
		return v < min ? min : (v > max ? max : v);
	}

	private String stationLabel(MapModel.Station station) {
		if (station == null) {
			return "";
		}
		String[] n = MapModel.splitName(station.name);
		return english && !n[1].isEmpty() ? n[1] : n[0];
	}

	private String routeLabel(String name) {
		String[] n = MapModel.splitName(name);
		return english && !n[1].isEmpty() ? n[1] : n[0];
	}

	/** 中英文字符串：english 为 true 时取英文 */
	private String s(String zh, String en) {
		return english ? en : zh;
	}

	private static String fmtDist(double meters) {
		if (meters >= 1000) {
			return String.format("%.1fkm", meters / 1000.0);
		}
		return Math.round(meters) + "m";
	}

	private static String fmtMinutes(double seconds) {
		int minutes = (int) Math.round(seconds / 60.0);
		if (minutes < 1) {
			minutes = 1;
		}
		return minutes + "min";
	}

	/** 工具栏按钮 */
	private static final class Btn {
		final String id;
		final int x;
		final int y;
		final String label;
		final String tooltip;
		final boolean active;

		Btn(String id, int x, int y, String label, String tooltip, boolean active) {
			this.id = id;
			this.x = x;
			this.y = y;
			this.label = label;
			this.tooltip = tooltip;
			this.active = active;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + TOOL_W && my >= y && my <= y + TOOL_W;
		}
	}
}
