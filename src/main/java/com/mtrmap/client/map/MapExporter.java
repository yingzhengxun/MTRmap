package com.mtrmap.client.map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.platform.Platform;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 把地图 / 纪念票根渲染成图片并落盘。
 *
 * <p>游戏内的界面是用 Minecraft 的 GUI 管线画的，没法直接抓成图片，所以这里用
 * Java2D 重新画一遍：公式、配色、图层顺序全部照搬网页 map.js 的 render()。产物存到
 * {@code <游戏目录>/mtrmap/} 下，文件名与网页的下载名一致。
 *
 * <p>只支持 PNG 与 JPG：WebP 需要额外的编解码库，游戏里不引入。
 */
public final class MapExporter {

	/** 导出图片最长边（与网页一致） */
	private static final int MAX_DIM = 4096;
	/** 留白（像素，与网页一致） */
	private static final int PADDING = 200;

	private MapExporter() {
	}

	// ===== 落盘位置 =====

	public static Path outputDirectory() {
		return Platform.getConfigDir().resolve("mtrmap");
	}

	private static File write(BufferedImage image, String name, String format, int quality) throws Exception {
		Path dir = outputDirectory();
		Files.createDirectories(dir);
		File file = dir.resolve(name).toFile();
		if ("jpg".equals(format)) {
			// ImageIO 的 JPEG 写出器不支持透明，先铺白底
			BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = rgb.createGraphics();
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			g.drawImage(image, 0, 0, null);
			g.dispose();
			writeJpeg(rgb, file, quality);
		} else {
			ImageIO.write(image, "png", file);
		}
		return file;
	}

	/** 按质量参数写 JPEG（ImageIO 自带写出器只吃默认质量，这里手工设置） */
	private static void writeJpeg(BufferedImage image, File file, int quality) throws Exception {
		javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		javax.imageio.plugins.jpeg.JPEGImageWriteParam param =
				new javax.imageio.plugins.jpeg.JPEGImageWriteParam(Locale.getDefault());
		param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality / 100f)));
		try (javax.imageio.stream.ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
			writer.setOutput(out);
			writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
		} finally {
			writer.dispose();
		}
	}

	// ===== 地图导出 =====

	/**
	 * 导出整张线网。
	 *
	 * @param format  {@code "png"} 或 {@code "jpg"}
	 * @param quality JPEG 质量 1..100（PNG 忽略）
	 * @return 写出的文件；没有数据或失败返回 null
	 */
	public static File exportMap(MapModel model, boolean showDepots, boolean dark, boolean english,
			String format, int quality) {
		double[] bounds = mapBounds(model, showDepots);
		if (bounds == null) {
			return null;
		}
		double mapW = Math.max(1, bounds[2] - bounds[0]);
		double mapH = Math.max(1, bounds[3] - bounds[1]);
		int expW;
		int expH;
		if (mapW > mapH) {
			expW = MAX_DIM;
			expH = Math.max(1, (int) Math.round(mapH / mapW * MAX_DIM));
		} else {
			expH = MAX_DIM;
			expW = Math.max(1, (int) Math.round(mapW / mapH * MAX_DIM));
		}
		double scale = Math.min((expW - PADDING * 2.0) / mapW, (expH - PADDING * 2.0) / mapH);
		if (scale <= 0 || Double.isNaN(scale)) {
			scale = 0.1;
		}
		double offX = (expW - mapW * scale) / 2 - bounds[0] * scale;
		double offY = (expH - mapH * scale) / 2 - bounds[1] * scale;

		BufferedImage image = new BufferedImage(expW, expH, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		try {
			g.setColor(dark ? new Color(0xFF0F0F16, true) : new Color(0xFFE8E8EE, true));
			g.fillRect(0, 0, expW, expH);

			Transform tf = new Transform(scale, offX, offY);
			drawDepots(g, model, tf, dark, scale);
			drawRoutes(g, model, tf, scale);
			drawStations(g, model, tf, dark, scale);
			drawTrains(g, model, tf, scale);
			drawPlayers(g, model, tf, scale, dark);

			String name = "mtrmap_" + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date())
					+ "." + format;
			return write(image, name, format, quality);
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.warn("导出地图图片失败", t);
			return null;
		} finally {
			g.dispose();
		}
	}

	/** 导出范围：与网页 calculateMapBounds 一致，只统计当前实际显示的内容 */
	private static double[] mapBounds(MapModel model, boolean showDepots) {
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
		if (showDepots) {
			for (MapModel.Depot depot : model.depots) {
				minX = Math.min(minX, Math.min(depot.x1, depot.x2));
				maxX = Math.max(maxX, Math.max(depot.x1, depot.x2));
				minZ = Math.min(minZ, Math.min(depot.z1, depot.z2));
				maxZ = Math.max(maxZ, Math.max(depot.z1, depot.z2));
				any = true;
			}
		}
		for (MapModel.Route route : model.routes) {
			for (List<double[]> seg : route.paths) {
				if (seg == null) {
					continue;
				}
				for (double[] pt : seg) {
					minX = Math.min(minX, pt[0]);
					maxX = Math.max(maxX, pt[0]);
					minZ = Math.min(minZ, pt[1]);
					maxZ = Math.max(maxZ, pt[1]);
					any = true;
				}
			}
		}
		for (MapModel.Train train : model.trains) {
			minX = Math.min(minX, train.x);
			maxX = Math.max(maxX, train.x);
			minZ = Math.min(minZ, train.z);
			maxZ = Math.max(maxZ, train.z);
			any = true;
		}
		for (MapModel.Player player : model.players) {
			minX = Math.min(minX, player.x);
			maxX = Math.max(maxX, player.x);
			minZ = Math.min(minZ, player.z);
			maxZ = Math.max(maxZ, player.z);
			any = true;
		}
		return any ? new double[]{minX, minZ, maxX, maxZ} : null;
	}

	private static void drawDepots(Graphics2D g, MapModel model, Transform tf, boolean dark, double scale) {
		for (MapModel.Depot depot : model.depots) {
			double x1 = tf.x(Math.min(depot.x1, depot.x2));
			double y1 = tf.y(Math.min(depot.z1, depot.z2));
			double x2 = tf.x(Math.max(depot.x1, depot.x2));
			double y2 = tf.y(Math.max(depot.z1, depot.z2));
			g.setColor(color(depot.color, 0.6f));
			g.fillRect((int) x1, (int) y1, (int) (x2 - x1), (int) (y2 - y1));
			g.setColor(color(depot.color, 0.9f));
			g.setStroke(new BasicStroke(1.5f));
			g.drawRect((int) x1, (int) y1, (int) (x2 - x1), (int) (y2 - y1));
			if (depot.path != null && depot.path.size() >= 2) {
				g.setColor(color(depot.color, 0.8f));
				g.setStroke(new BasicStroke(1f));
				strokePolyline(g, depot.path, tf);
			} else if (depot.firstPlatformX != null) {
				g.setColor(color(depot.color, 0.8f));
				g.setStroke(new BasicStroke(1f));
				g.draw(new Line2D.Double(tf.x(depot.firstPlatformX), tf.y(depot.firstPlatformZ),
						tf.x(depot.centerX), tf.y(depot.centerZ)));
			}
			if (scale > 0.05) {
				String[] n = MapModel.splitName(depot.name);
				double cx = (x1 + x2) / 2;
				double cy = (y1 + y2) / 2;
				g.setColor(dark ? Color.WHITE : new Color(0xFF222233, true));
				if (!n[1].isEmpty()) {
					drawCenter(g, n[0], font(11), cx, cy - 5);
					drawCenter(g, n[1], font(9), cx, cy + 6);
				} else {
					drawCenter(g, n[0], font(11), cx, cy);
				}
			}
		}
	}

	private static void drawRoutes(Graphics2D g, MapModel model, Transform tf, double scale) {
		float thickness = (float) Math.max(2, 4 * Math.sqrt(scale));
		for (MapModel.Route route : model.routes) {
			if (route.stations.size() < 2) {
				continue;
			}
			g.setColor(color(route.color, 0.9f));
			g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			for (int i = 0; i < route.stations.size() - 1; i++) {
				List<double[]> seg = i < route.paths.size() ? route.paths.get(i) : null;
				if (seg != null && seg.size() >= 2) {
					strokePolyline(g, seg, tf);
				} else {
					MapModel.Station a = model.station(route.stations.get(i));
					MapModel.Station b = model.station(route.stations.get(i + 1));
					if (a == null || b == null) {
						continue;
					}
					g.draw(new Line2D.Double(tf.x(a.x), tf.y(a.z), tf.x(b.x), tf.y(b.z)));
				}
			}
		}
	}

	private static void drawStations(Graphics2D g, MapModel model, Transform tf, boolean dark, double scale) {
		double radius = Math.max(4, 6 * Math.sqrt(scale));
		for (MapModel.Station station : model.stations) {
			g.setStroke(new BasicStroke(2f));
			g.setColor(Color.WHITE);
			if (station.isInterchange() && station.hasBounds()) {
				double[] cap = capsule(station, tf, radius * 2);
				double r = cap[2];
				double half = cap[3];
				boolean vertical = cap[4] > 0;
				double w = vertical ? 2 * r : 2 * (half + r);
				double h = vertical ? 2 * (half + r) : 2 * r;
				RoundRectangle2D shape = new RoundRectangle2D.Double(cap[0] - w / 2, cap[1] - h / 2, w, h, 2 * r, 2 * r);
				g.fill(shape);
				g.setColor(color(station.color, 1f));
				g.draw(shape);
			} else {
				double cx = tf.x(station.x);
				double cy = tf.y(station.z);
				java.awt.geom.Ellipse2D circle =
						new java.awt.geom.Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2);
				g.fill(circle);
				g.setColor(color(station.color, 1f));
				g.draw(circle);
			}
		}
		// 站名：先放换乘站，再放普通站，重叠的跳过
		if (scale <= 0.05) {
			return;
		}
		List<int[]> placed = new ArrayList<>();
		placeNames(g, model, tf, radius, true, dark, placed);
		placeNames(g, model, tf, radius, false, dark, placed);
	}

	private static void placeNames(Graphics2D g, MapModel model, Transform tf, double radius,
			boolean interchange, boolean dark, List<int[]> placed) {
		Font main = font(12);
		Font trans = font(9);
		for (MapModel.Station station : model.stations) {
			if (station.isInterchange() != interchange || placed.size() >= 400) {
				continue;
			}
			String[] n = MapModel.splitName(station.name);
			if (n[0].isEmpty()) {
				continue;
			}
			double labelX;
			double labelTop;
			if (station.isInterchange() && station.hasBounds()) {
				double[] cap = capsule(station, tf, radius * 2);
				labelX = cap[0];
				labelTop = cap[1] - (cap[4] > 0 ? cap[2] + cap[3] : cap[2]);
			} else {
				labelX = tf.x(station.x);
				labelTop = tf.y(station.z) - radius;
			}
			FontMetrics fmMain = g.getFontMetrics(main);
			FontMetrics fmTrans = g.getFontMetrics(trans);
			int mainW = fmMain.stringWidth(n[0]);
			int transW = n[1].isEmpty() ? 0 : fmTrans.stringWidth(n[1]);
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
			g.setColor(dark ? Color.WHITE : new Color(0xFF222233, true));
			drawCenter(g, n[0], main, labelX, baseline - 9);
			if (!n[1].isEmpty()) {
				drawCenter(g, n[1], trans, labelX, baseline);
			}
		}
	}

	private static boolean overlaps(List<int[]> placed, int left, int right, int top, int bottom) {
		for (int[] r : placed) {
			if (left < r[1] && right > r[0] && top < r[3] && bottom > r[2]) {
				return true;
			}
		}
		return false;
	}

	private static void drawTrains(Graphics2D g, MapModel model, Transform tf, double scale) {
		double size = Math.max(7, 11 * Math.sqrt(scale));
		for (MapModel.Train train : model.trains) {
			double cx = tf.x(train.x);
			double cy = tf.y(train.z);
			java.awt.geom.RoundRectangle2D body = new java.awt.geom.RoundRectangle2D.Double(
					cx - size / 2, cy - size / 2, size, size * 0.7, 3, 3);
			g.setColor(new Color(30, 30, 55, 242));
			g.fill(body);
			g.setColor(color(train.routeColor, 0.95f));
			g.setStroke(new BasicStroke(2f));
			g.draw(body);
			g.fill(new java.awt.geom.Rectangle2D.Double(cx - size * 0.2, cy - size * 0.18, size * 0.4, size * 0.18));
		}
	}

	private static void drawPlayers(Graphics2D g, MapModel model, Transform tf, double scale, boolean dark) {
		double size = Math.max(20, 32 * Math.sqrt(scale));
		for (MapModel.Player player : model.players) {
			double cx = tf.x(player.x);
			double cy = tf.y(player.z);
			g.setColor(new Color(0xFF50FA7B, true));
			g.fill(new java.awt.geom.Ellipse2D.Double(cx - size / 2, cy - size / 2, size, size));
			g.setStroke(new BasicStroke(2f));
			g.draw(new java.awt.geom.Rectangle2D.Double(cx - size / 2, cy - size / 2, size, size));
			g.setFont(fontBold(12));
			g.setColor(new Color(0xFF50FA7B, true));
			drawCenter(g, player.name, fontBold(12), cx, cy + size / 2 + 12);
		}
	}

	// ===== 纪念票根 =====

	/**
	 * 生成并保存一张纪念票根（与网页 buildTicketImage 完全同一版式）。
	 *
	 * @return 写出的文件；失败返回 null
	 */
	public static File saveTicket(JsonObject trip, boolean english) {
		if (trip == null) {
			return null;
		}
		final int w = 840;
		final int h = 420;
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		try {
			boolean completed = "completed".equals(str(trip, "status"));
			g.setColor(new Color(0xFFF7F3E8, true));
			g.fillRect(0, 0, w, h);
			g.setColor(new Color(0xFF2A2A3A, true));
			g.setStroke(new BasicStroke(6f));
			g.drawRect(18, 18, w - 36, h - 36);

			g.setColor(new Color(0xFF2A2A3A, true));
			g.setFont(fontBold(32));
			drawLeft(g, english ? "MTR Souvenir Ticket" : "MTR 纪念票根", 48, 92);

			g.setColor(completed ? new Color(0xFF1F9D55, true) : new Color(0xFF8A8F98, true));
			g.setFont(fontBold(22));
			drawRight(g, completed ? (english ? "Completed" : "已完成") : (english ? "Stopped" : "已停止"), w - 48, 92);

			g.setColor(new Color(0xFFC9C0A8, true));
			g.setStroke(new BasicStroke(2f));
			g.draw(new Line2D.Double(48, 118, w - 48, 118));

			String from = tripPointName(trip, "from");
			String to = tripPointName(trip, "to");
			g.setColor(new Color(0xFF5A5A6E, true));
			g.setFont(font(15));
			drawLeft(g, english ? "FROM" : "起点", 48, 166);
			drawLeft(g, english ? "TO" : "终点", 470, 166);
			g.setColor(new Color(0xFF1A1A2E, true));
			g.setFont(fontBold(40));
			drawLeft(g, clip(g, from, 340), 48, 218);
			drawLeft(g, clip(g, to, 340), 470, 218);
			g.setColor(new Color(0xFFC9C0A8, true));
			g.setFont(fontBold(38));
			drawLeft(g, "→", 410, 218);

			String[][] cells = {
					{english ? "DATE" : "日期", formatDate((long) num(trip, "time"))},
					{english ? "DISTANCE" : "里程", fmtDist(num(trip, "dist"))},
					{english ? "TIME" : "用时", fmtMinutes(num(trip, "timeSec"))},
					{english ? "TRANSFERS" : "换乘", String.valueOf((int) num(trip, "transfers"))},
			};
			for (int i = 0; i < cells.length; i++) {
				int x = 48 + (i % 2) * 422;
				int y = 285 + (i / 2) * 46;
				g.setColor(new Color(0xFF5A5A6E, true));
				g.setFont(font(14));
				drawLeft(g, cells[i][0], x, y);
				g.setColor(new Color(0xFF1A1A2E, true));
				g.setFont(fontBold(20));
				drawLeft(g, cells[i][1], x + 92, y);
			}

			g.setColor(new Color(0xFF9A9382, true));
			g.setFont(font(13));
			drawLeft(g, english ? "MTR Map · travel souvenir" : "MTR Map · 旅途纪念", 48, h - 40);
			int bx = w - 260;
			for (int i = 0; i < 42; i++) {
				g.setColor(i % 3 == 0 ? new Color(0xFF2A2A3A, true) : new Color(0xFF6A6478, true));
				double bw = (i % 2 == 0) ? 3 : 1.5;
				g.fill(new java.awt.geom.Rectangle2D.Double(bx, h - 60, bw, 26));
				bx += bw + 2.5;
			}

			String id = str(trip, "id");
			if (id.isEmpty()) {
				id = String.valueOf(System.currentTimeMillis());
			}
			return write(image, "mtr-ticket-" + id + ".png", "png", 100);
		} catch (Throwable t) {
			MtrMapCommon.LOGGER.warn("生成纪念票根失败", t);
			return null;
		} finally {
			g.dispose();
		}
	}

	// ===== 小工具 =====

	/** 世界坐标 → 图片坐标 */
	private static final class Transform {
		final double scale;
		final double offX;
		final double offY;

		Transform(double scale, double offX, double offY) {
			this.scale = scale;
			this.offX = offX;
			this.offY = offY;
		}

		double x(double worldX) {
			return worldX * scale + offX;
		}

		double y(double worldZ) {
			return worldZ * scale + offY;
		}
	}

	private static void strokePolyline(Graphics2D g, List<double[]> points, Transform tf) {
		GeneralPath path = new GeneralPath();
		for (int i = 0; i < points.size(); i++) {
			double x = tf.x(points.get(i)[0]);
			double y = tf.y(points.get(i)[1]);
			if (i == 0) {
				path.moveTo(x, y);
			} else {
				path.lineTo(x, y);
			}
		}
		g.draw(path);
	}

	/** 换乘站胶囊几何：{cx, cy, r, half, vertical(1/0)} */
	private static double[] capsule(MapModel.Station station, Transform tf, double minThick) {
		double x1 = tf.x(station.bx1);
		double y1 = tf.y(station.bz1);
		double x2 = tf.x(station.bx2);
		double y2 = tf.y(station.bz2);
		double cx = (x1 + x2) / 2;
		double cy = (y1 + y2) / 2;
		double w = Math.abs(x2 - x1);
		double h = Math.abs(y2 - y1);
		boolean vertical = h > w;
		double r = Math.max(minThick / 2, (vertical ? w : h) / 2);
		double half = Math.max((vertical ? h : w) / 2 - r, r * 0.8);
		return new double[]{cx, cy, r, half, vertical ? 1 : 0};
	}

	private static void drawLeft(Graphics2D g, String text, double x, double baseline) {
		g.drawString(text, (float) x, (float) baseline);
	}

	private static void drawRight(Graphics2D g, String text, double right, double baseline) {
		int w = g.getFontMetrics().stringWidth(text);
		g.drawString(text, (float) (right - w), (float) baseline);
	}

	private static void drawCenter(Graphics2D g, String text, Font font, double cx, double baseline) {
		g.setFont(font);
		int w = g.getFontMetrics().stringWidth(text);
		g.drawString(text, (float) (cx - w / 2.0), (float) baseline);
	}

	private static String clip(Graphics2D g, String text, int maxWidth) {
		FontMetrics fm = g.getFontMetrics();
		if (fm.stringWidth(text) <= maxWidth) {
			return text;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			if (fm.stringWidth(sb.toString() + text.charAt(i) + "…") > maxWidth) {
				break;
			}
			sb.append(text.charAt(i));
		}
		return sb + "…";
	}

	private static Color color(int argb, float alpha) {
		int a = (argb >>> 24) & 0xFF;
		if (a == 0) {
			a = 255;
		}
		return new Color((argb & 0x00FFFFFF) | (Math.round(a * Math.max(0f, Math.min(1f, alpha))) << 24), true);
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

	private static String tripPointName(JsonObject trip, String key) {
		if (!trip.has(key) || !trip.get(key).isJsonObject()) {
			return "-";
		}
		String name = str(trip.getAsJsonObject(key), "name");
		return name.isEmpty() ? "-" : name;
	}

	private static String formatDate(long epochSeconds) {
		if (epochSeconds <= 0) {
			return "-";
		}
		return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(epochSeconds * 1000L));
	}

	private static String fmtDist(double meters) {
		if (meters >= 1000) {
			return String.format(Locale.ROOT, "%.1fkm", meters / 1000.0);
		}
		return Math.round(meters) + "m";
	}

	private static String fmtMinutes(double seconds) {
		int minutes = (int) Math.round(seconds / 60.0);
		return minutes + "min";
	}

	// ===== 字体 =====

	private static String fontFamily;

	/** 挑一个装了中文的字体：Java2D 的逻辑字体在少数系统上会缺 CJK 字形 */
	private static String fontFamily() {
		if (fontFamily != null) {
			return fontFamily;
		}
		String[] candidates = {"Microsoft YaHei", "微软雅黑", "SimHei", "Noto Sans CJK SC", "Source Han Sans SC", "Dialog"};
		try {
			List<String> available = java.util.Arrays.asList(
					GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT));
			for (String candidate : candidates) {
				if (available.contains(candidate)) {
					fontFamily = candidate;
					return fontFamily;
				}
			}
		} catch (Throwable ignored) {
			// 无图形环境时退回逻辑字体
		}
		fontFamily = "SansSerif";
		return fontFamily;
	}

	private static Font font(int size) {
		return new Font(fontFamily(), Font.PLAIN, size);
	}

	private static Font fontBold(int size) {
		return new Font(fontFamily(), Font.BOLD, size);
	}
}
