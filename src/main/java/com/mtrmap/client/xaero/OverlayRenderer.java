package com.mtrmap.client.xaero;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=1.20.1 {
import org.joml.Matrix4f;
//?} else {
/*import com.mojang.math.Matrix4f;
*///?}

/**
 * Xaero 世界地图上的 MTR 线网叠加绘制。
 *
 * 所有要画的东西都是"世界坐标 → 屏幕坐标"的换算，所以由调用方通过 {@link Projection}
 * 传入换算方式（世界地图固定北朝上，换算就是平移 + 缩放）。
 *
 * 绘制方式：直接向 GUI 的 VertexConsumer 写四边形。
 * 之所以不用 GuiGraphics.fill()，是因为它每调用一次都会 flush（一次 draw call），
 * 线网折线点很多时会严重掉帧；这里统一批量提交，最后只 flush 一次。
 * 顶点格式是 POSITION_COLOR（无 UV），与 fill 内部写法一致。
 *
 * 具体怎么拿到 VertexConsumer、以及文字怎么画，各版本不同（1.19.2 没有 GuiGraphics），
 * 这部分差异全部由 {@link GuiSink} 承担。
 *
 * 裁剪：不用 GuiGraphics.enableScissor，因为 1.20.1 的实现不感知 PoseStack，
 * 参数必须是未经变换的 GUI 坐标，容易出错且无法跟随绘制变换。
 * 这里改为对每条线段做 Liang-Barsky 几何裁剪，并把车站圆点限制在裁剪框内。
 */
public final class OverlayRenderer {

	/**
	 * 世界坐标 → 屏幕坐标的换算。
	 * x 与 z 一起传入：换算未必轴对齐（例如调用方的投影里带旋转时两个轴会耦合），
	 * 所以两个方法都接收完整的 (worldX, worldZ)。
	 */
	public interface Projection {
		float sx(double worldX, double worldZ);

		float sz(double worldX, double worldZ);

		/** 当前每个方块对应多少屏幕（GUI 缩放后）像素，用于换算线宽和站点半径 */
		float pxPerBlock();
	}

	/** 折线抽稀阈值（像素）：屏幕距离小于该值的点不再单独出顶点 */
	private static final float DECIMATE_PX = 1.2f;
	/** 低于该缩放（每方块像素数）时不画站名，否则文字会糊成一团 */
	private static final float LABEL_MIN_PPB = 0.35f;
	/** 换乘站跑道形标记的直边半长下限（相对半径）：太短的胶囊看不出形状 */
	private static final float STADIUM_MIN_RATIO = 0.8f;
	/** 车站圆点的底色 */
	private static final float[] WHITE = {1f, 1f, 1f, 1f};

	/** 几何裁剪使用的可复用数组，避免每段线段都分配对象 */
	private static final float[] SEG = new float[4];

	private OverlayRenderer() {
	}

	/**
	 * @param showDepots 是否绘制车厂及其出入库连接线
	 * @param clipMinX   裁剪框（GUI 坐标），线网与圆点都不会超出这个范围
	 */
	public static void draw(GuiSink sink, Projection proj, OverlayData.Snapshot snap,
							float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
							boolean showDepots) {
		if (snap == null || proj == null) {
			return;
		}
		Matrix4f mat = sink.matrix();
		VertexConsumer vc = sink.vertices();

		float ppb = proj.pxPerBlock();
		// 线宽随缩放变化，但夹在合理区间内，避免极小或极粗
		float halfLine = clamp(1.6f * (float) Math.sqrt(ppb), 1.2f, 6f);
		float stationRadius = clamp(3.6f * (float) Math.sqrt(ppb), 3f, 12f);

		// 1) 车厂（矩形 + 连接线），画在线路下方
		if (showDepots) {
			for (OverlayData.Depot d : snap.depots) {
				// 四个角都投影，不假设投影后仍是轴对齐矩形（调用方带旋转时会变成任意四边形）
				float[] q = POLY_QUAD;
				q[0] = proj.sx(d.x1, d.z1);
				q[1] = proj.sz(d.x1, d.z1);
				q[2] = proj.sx(d.x2, d.z1);
				q[3] = proj.sz(d.x2, d.z1);
				q[4] = proj.sx(d.x2, d.z2);
				q[5] = proj.sz(d.x2, d.z2);
				q[6] = proj.sx(d.x1, d.z2);
				q[7] = proj.sz(d.x1, d.z2);
				// 填充取「车厂 ∩ 裁剪框」：可见部分照常显示，超出可视范围的部分不会画到框外
				int fillVerts = clipRect(q, 4, clipMinX, clipMinY, clipMaxX, clipMaxY);
				if (fillVerts >= 3) {
					fillFan(vc, mat, POLY_A, fillVerts, argb(d.color, 0.35f));
				}
				// 边框：四条边各自按段裁剪，避免在被裁掉的那条边上画出假边框
				float[] bc = argb(d.color, 0.9f);
				for (int i = 0; i < 4; i++) {
					int j = (i + 1) & 3;
					drawClippedSegment(vc, mat, q[i * 2], q[i * 2 + 1], q[j * 2], q[j * 2 + 1], 0.5f, bc,
							clipMinX, clipMinY, clipMaxX, clipMaxY);
				}
				// 与车站的连接线（入库/出库线走真实轨道，按段裁剪）
				if (d.linkXs != null && d.linkXs.length >= 2) {
					float[] lc = argb(d.color, 0.85f);
					float prevX = proj.sx(d.linkXs[0], d.linkZs[0]);
					float prevY = proj.sz(d.linkXs[0], d.linkZs[0]);
					for (int i = 1; i < d.linkXs.length; i++) {
						float cx = proj.sx(d.linkXs[i], d.linkZs[i]);
						float cy = proj.sz(d.linkXs[i], d.linkZs[i]);
						drawClippedSegment(vc, mat, prevX, prevY, cx, cy, 0.8f, lc,
								clipMinX, clipMinY, clipMaxX, clipMaxY);
						prevX = cx;
						prevY = cy;
					}
				}
			}
		}

		// 2) 线路折线
		for (OverlayData.Line line : snap.lines) {
			float[] c = argb(line.color, 1f);
			int n = line.xs.length;
			float prevX = 0f;
			float prevY = 0f;
			boolean has = false;
			for (int i = 0; i < n; i++) {
				float px = proj.sx(line.xs[i], line.zs[i]);
				float py = proj.sz(line.xs[i], line.zs[i]);
				if (line.brk[i]) {
					// 新的子折线，起点必须出顶点
					prevX = px;
					prevY = py;
					has = true;
					continue;
				}
				if (!has) {
					prevX = px;
					prevY = py;
					has = true;
					continue;
				}
				// 抽稀：屏幕距离太近就跳过，但子折线末点必须保留
				boolean last = i == n - 1 || line.brk[i + 1];
				float dx = px - prevX;
				float dy = py - prevY;
				if (!last && dx * dx + dy * dy < DECIMATE_PX * DECIMATE_PX) {
					continue;
				}
				drawClippedSegment(vc, mat, prevX, prevY, px, py, halfLine, c,
						clipMinX, clipMinY, clipMaxX, clipMaxY);
				prevX = px;
				prevY = py;
			}
		}

		// 3) 车站标记（白底 + 线路色描边），画在线路之上。
		//    普通车站是圆点；换乘站是跑道形，长度与朝向跟着该站的站台分布走。
		for (OverlayData.Station st : snap.stations) {
			markerGeometry(st, proj, stationRadius);
			// 只画完全落在裁剪框内的标记，保证不溢出边框
			if (!markerInside(clipMinX, clipMinY, clipMaxX, clipMaxY)) {
				continue;
			}
			float ringHalf = Math.max(1f, MARKER[M_R] * 0.32f);
			fillMarker(vc, mat);
			ringMarker(vc, mat, ringHalf, argb(st.color, 1f));
		}

		// 先提交所有四边形，再画站名文字。
		// 文字走的是另一个 RenderType，如果不先 flush，四边形会在文字之后提交，
		// 反而盖住文字。
		sink.flush();

		// 4) 站名（按 "|" 拆成主名 + 译名两行）。缩得太小时文字会糊成一团，直接不画
		if (ppb < LABEL_MIN_PPB) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		float labelScale = clamp(ppb, 0.6f, 1.4f);
		labelCount = 0;
		// 换乘站的名字更重要，先占位；普通站与已放下的站名重叠就跳过，
		// 否则缩小后几个站挨得近时站名会糊成一团。
		drawStationLabels(sink, font, proj, snap, stationRadius, labelScale,
				clipMinX, clipMinY, clipMaxX, clipMaxY, true);
		drawStationLabels(sink, font, proj, snap, stationRadius, labelScale,
				clipMinX, clipMinY, clipMaxX, clipMaxY, false);
	}

	/**
	 * 画一批站名。{@code interchangeOnly} 为 true 时只画换乘站，否则只画普通站。
	 * 站名之间做矩形相交判定，重叠的站名直接不画（换乘站优先占位）。
	 */
	private static void drawStationLabels(GuiSink sink, Font font, Projection proj, OverlayData.Snapshot snap,
										  float stationRadius, float labelScale,
										  float minX, float minY, float maxX, float maxY,
										  boolean interchangeOnly) {
		for (OverlayData.Station st : snap.stations) {
			if (st.interchange != interchangeOnly || labelCount >= MAX_LABELS) {
				continue;
			}
			markerGeometry(st, proj, stationRadius);
			if (!markerInside(minX, minY, maxX, maxY)) {
				continue;
			}
			String[] names = splitName(st.name);
			if (names[0].isEmpty()) {
				continue;
			}
			// 站名放在标记正上方
			float top = MARKER[M_CY]
					- (MARKER[M_VERTICAL] > 0f ? MARKER[M_HALF] + MARKER[M_R] : MARKER[M_R]);
			float textY = top - 4f * labelScale;
			boolean twoLines = names.length > 1;
			float width = font.width(names[0]);
			if (twoLines) {
				width = Math.max(width, font.width(names[1]) * 0.82f);
			}
			// 文字是居中画在标记正上方的，包围盒按同样的规则算，再留一点间距
			float half = width * labelScale / 2f + 2f * labelScale;
			float left = MARKER[M_CX] - half;
			float right = MARKER[M_CX] + half;
			float boxTop = textY - labelScale;
			float boxBottom = textY + 9f * labelScale * (twoLines ? 1.82f : 1f) + labelScale;
			if (overlapsPlacedLabel(left, boxTop, right, boxBottom)) {
				continue;
			}
			addPlacedLabel(left, boxTop, right, boxBottom);
			drawScaledString(sink, font, names[0], MARKER[M_CX], textY, labelScale);
			if (twoLines) {
				drawScaledString(sink, font, names[1], MARKER[M_CX], textY + 9f * labelScale, labelScale * 0.82f);
			}
		}
	}

	// ===== 站名防重叠 =====
	// 一帧内已经画出来的站名包围盒（left, top, right, bottom 依次四个），
	// 用来挡掉会和它们叠在一起的站名。上限用于控制最坏情况下的比较次数。
	private static final int MAX_LABELS = 256;
	private static final float[] LABEL_BOXES = new float[MAX_LABELS * 4];
	private static int labelCount;

	/** 待画的站名是否与已画出的站名重叠 */
	private static boolean overlapsPlacedLabel(float left, float top, float right, float bottom) {
		for (int i = 0; i < labelCount; i++) {
			int o = i * 4;
			if (left < LABEL_BOXES[o + 2] && right > LABEL_BOXES[o]
					&& top < LABEL_BOXES[o + 3] && bottom > LABEL_BOXES[o + 1]) {
				return true;
			}
		}
		return false;
	}

	/** 记下已经画出的站名包围盒 */
	private static void addPlacedLabel(float left, float top, float right, float bottom) {
		int o = labelCount * 4;
		LABEL_BOXES[o] = left;
		LABEL_BOXES[o + 1] = top;
		LABEL_BOXES[o + 2] = right;
		LABEL_BOXES[o + 3] = bottom;
		labelCount++;
	}

	/** 站名按 "|" 拆成主名与译名两行显示（与网页地图保持一致） */
	private static String[] splitName(String name) {
		if (name == null) {
			return new String[]{""};
		}
		int idx = name.indexOf('|');
		if (idx < 0) {
			return new String[]{name.trim()};
		}
		String main = name.substring(0, idx).trim();
		String trans = name.substring(idx + 1).trim();
		return trans.isEmpty() ? new String[]{main} : new String[]{main, trans};
	}

	/** 居中绘制白色站名，可缩放 */
	private static void drawScaledString(GuiSink sink, Font font, String text, float centerX, float y,
										 float scale) {
		if (text == null || text.isEmpty()) {
			return;
		}
		sink.push();
		sink.translate(centerX, y, 0f);
		sink.scale(scale);
		sink.text(font, text, 0f, 0f, 0xFFFFFFFF, true);
		sink.pop();
	}

	// ===== 底层四边形绘制 =====

	/**
	 * 先做几何裁剪再画粗线；线段完全在裁剪框外时直接跳过。
	 * 裁剪框向内收 halfLine，保证描边不会越界。
	 */
	private static void drawClippedSegment(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2,
										   float half, float[] c,
										   float minX, float minY, float maxX, float maxY) {
		SEG[0] = x1;
		SEG[1] = y1;
		SEG[2] = x2;
		SEG[3] = y2;
		float inset = half + 0.5f;
		if (!clipSegment(minX + inset, minY + inset, maxX - inset, maxY - inset)) {
			return;
		}
		thickLine(vc, m, SEG[0], SEG[1], SEG[2], SEG[3], half, c);
	}

	/**
	 * Liang-Barsky 线段裁剪：把 {@link #SEG} 中的线段裁剪到矩形内。
	 * 被完全裁掉时返回 false，否则把裁剪后的端点写回 SEG。
	 */
	private static boolean clipSegment(float minX, float minY, float maxX, float maxY) {
		if (maxX <= minX || maxY <= minY) {
			return false;
		}
		float x1 = SEG[0];
		float y1 = SEG[1];
		float dx = SEG[2] - x1;
		float dy = SEG[3] - y1;
		float t0 = 0f;
		float t1 = 1f;
		// 四组边界：左、右、上、下
		float[] p = {-dx, dx, -dy, dy};
		float[] q = {x1 - minX, maxX - x1, y1 - minY, maxY - y1};
		for (int i = 0; i < 4; i++) {
			if (p[i] == 0f) {
				// 平行于该边界：线段在外侧则整体丢弃
				if (q[i] < 0f) {
					return false;
				}
			} else {
				float r = q[i] / p[i];
				if (p[i] < 0f) {
					if (r > t1) {
						return false;
					}
					if (r > t0) {
						t0 = r;
					}
				} else {
					if (r < t0) {
						return false;
					}
					if (r < t1) {
						t1 = r;
					}
				}
			}
		}
		SEG[0] = x1 + t0 * dx;
		SEG[1] = y1 + t0 * dy;
		SEG[2] = x1 + t1 * dx;
		SEG[3] = y1 + t1 * dy;
		return true;
	}

	/** 车厂矩形投影后的四个角（xy 交错）；另加两块多边形裁剪缓冲 */
	private static final float[] POLY_QUAD = new float[8];
	private static final float[] POLY_A = new float[16];
	private static final float[] POLY_B = new float[16];

	/**
	 * Sutherland-Hodgman 凸多边形裁剪：把 {@code poly} 的前 count 个顶点（xy 交错）裁到矩形内。
	 * 结果写在 {@link #POLY_A} 里，返回裁剪后的顶点数；完全落在框外时返回 0。
	 * 车厂投影后不一定是轴对齐矩形（调用方带旋转时会变成任意四边形），
	 * 所以这里必须用通用的多边形裁剪。
	 */
	private static int clipRect(float[] poly, int count, float minX, float minY, float maxX, float maxY) {
		System.arraycopy(poly, 0, POLY_A, 0, count * 2);
		int n = clipPolyEdge(POLY_A, count, 0, minX, true, POLY_B);
		if (n == 0) {
			return 0;
		}
		n = clipPolyEdge(POLY_B, n, 0, maxX, false, POLY_A);
		if (n == 0) {
			return 0;
		}
		n = clipPolyEdge(POLY_A, n, 1, minY, true, POLY_B);
		if (n == 0) {
			return 0;
		}
		return clipPolyEdge(POLY_B, n, 1, maxY, false, POLY_A);
	}

	/**
	 * 按一条边界裁剪多边形：axis=0 裁 x、axis=1 裁 y；
	 * keepGreater 为 true 时保留坐标 ≥ limit 的部分，否则保留 ≤ limit 的部分。
	 */
	private static int clipPolyEdge(float[] src, int count, int axis, float limit, boolean keepGreater,
									float[] dst) {
		int out = 0;
		for (int i = 0; i < count; i++) {
			int j = i + 1 == count ? 0 : i + 1;
			float ai = src[i * 2 + axis];
			float aj = src[j * 2 + axis];
			boolean keepI = keepGreater ? ai >= limit : ai <= limit;
			boolean keepJ = keepGreater ? aj >= limit : aj <= limit;
			if (keepI) {
				dst[out * 2] = src[i * 2];
				dst[out * 2 + 1] = src[i * 2 + 1];
				out++;
			}
			if (keepI != keepJ) {
				float t = (limit - ai) / (aj - ai);
				dst[out * 2] = src[i * 2] + t * (src[j * 2] - src[i * 2]);
				dst[out * 2 + 1] = src[i * 2 + 1] + t * (src[j * 2 + 1] - src[i * 2 + 1]);
				out++;
			}
		}
		return out;
	}

	/** 把凸多边形当三角扇填充 */
	private static void fillFan(VertexConsumer vc, Matrix4f m, float[] poly, int count, float[] c) {
		for (int i = 1; i < count - 1; i++) {
			quad(vc, m, poly[0], poly[1], poly[i * 2], poly[i * 2 + 1],
					poly[(i + 1) * 2], poly[(i + 1) * 2 + 1], poly[0], poly[1], c);
		}
	}

	/** 以 (x1,y1)-(x2,y2) 为轴线、half 为半宽的粗线段 */
	private static void thickLine(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2,
								  float half, float[] c) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.001f) {
			return;
		}
		float nx = -dy / len * half;
		float ny = dx / len * half;
		quad(vc, m, x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, c);
	}

	// ===== 车站标记几何 =====
	// 渲染线程单线程执行，这里用一块可复用数组在「算几何」与「画标记」之间传值，避免每站分配对象。
	/** 标记几何：中心 cx/cy、半径、两端圆心到中心的距离、是否竖放 */
	private static final float[] MARKER = new float[5];
	private static final int M_CX = 0;
	private static final int M_CY = 1;
	private static final int M_R = 2;
	private static final int M_HALF = 3;
	private static final int M_VERTICAL = 4;

	/**
	 * 计算车站标记的屏幕几何，写入 {@link #MARKER}。
	 *
	 * 普通车站：以车站点位为圆心的圆点。
	 * 换乘站：以该站「全部站台中心的包围盒」为范围画跑道形，
	 * 这样标记能盖住经过该站的所有线路；站台沿南北排开时包围盒更高，标记自动竖过来。
	 * 半径至少取普通车站大小，保证再扁的站也是个看得清的胶囊。
	 */
	private static void markerGeometry(OverlayData.Station st, Projection proj, float minRadius) {
		MARKER[M_R] = minRadius;
		MARKER[M_HALF] = 0f;
		MARKER[M_VERTICAL] = 0f;
		float[] b = st.bounds;
		if (!st.interchange || b == null) {
			MARKER[M_CX] = proj.sx(st.x, st.z);
			MARKER[M_CY] = proj.sz(st.x, st.z);
			return;
		}
		float ax = proj.sx(b[0], b[1]);
		float ay = proj.sz(b[0], b[1]);
		float bx = proj.sx(b[2], b[3]);
		float by = proj.sz(b[2], b[3]);
		float w = Math.abs(bx - ax);
		float h = Math.abs(by - ay);
		boolean vertical = h > w;
		float r = Math.max(minRadius, (vertical ? w : h) / 2f);
		MARKER[M_CX] = (ax + bx) / 2f;
		MARKER[M_CY] = (ay + by) / 2f;
		MARKER[M_R] = r;
		MARKER[M_HALF] = Math.max((vertical ? h : w) / 2f - r, r * STADIUM_MIN_RATIO);
		MARKER[M_VERTICAL] = vertical ? 1f : 0f;
	}

	/** 标记是否完全落在裁剪框内；只有完全可见才画，保证不溢出边框 */
	private static boolean markerInside(float minX, float minY, float maxX, float maxY) {
		float r = MARKER[M_R];
		float half = MARKER[M_HALF];
		boolean vertical = MARKER[M_VERTICAL] > 0f;
		float halfW = vertical ? r : half + r;
		float halfH = vertical ? half + r : r;
		return MARKER[M_CX] - halfW >= minX && MARKER[M_CX] + halfW <= maxX
				&& MARKER[M_CY] - halfH >= minY && MARKER[M_CY] + halfH <= maxY;
	}

	/** 填充标记：普通车站是圆点，换乘站是「两个圆帽 + 中间矩形」的跑道形 */
	private static void fillMarker(VertexConsumer vc, Matrix4f m) {
		float cx = MARKER[M_CX];
		float cy = MARKER[M_CY];
		float r = MARKER[M_R];
		float half = MARKER[M_HALF];
		if (half <= 0f) {
			circle(vc, m, cx, cy, r, WHITE);
		} else if (MARKER[M_VERTICAL] > 0f) {
			circle(vc, m, cx, cy - half, r, WHITE);
			circle(vc, m, cx, cy + half, r, WHITE);
			quad(vc, m, cx - r, cy - half, cx - r, cy + half, cx + r, cy + half, cx + r, cy - half, WHITE);
		} else {
			circle(vc, m, cx - half, cy, r, WHITE);
			circle(vc, m, cx + half, cy, r, WHITE);
			quad(vc, m, cx - half, cy - r, cx - half, cy + r, cx + half, cy + r, cx + half, cy - r, WHITE);
		}
	}

	/**
	 * 标记描边：圆点用一圈线段近似；跑道形沿边界走一圈
	 * （两个半圆 + 两条直边，长轴方向由 MARKER 的竖放标志决定）。
	 */
	private static void ringMarker(VertexConsumer vc, Matrix4f m, float ringHalf, float[] c) {
		float cx = MARKER[M_CX];
		float cy = MARKER[M_CY];
		float r = MARKER[M_R] - ringHalf;
		float len = MARKER[M_HALF];
		if (len <= 0f) {
			int seg = r < 5 ? 10 : 16;
			float prevX = 0f;
			float prevY = 0f;
			for (int i = 0; i <= seg; i++) {
				double ang = i * 2 * Math.PI / seg;
				float x = cx + (float) Math.cos(ang) * r;
				float y = cy + (float) Math.sin(ang) * r;
				if (i > 0) {
					thickLine(vc, m, prevX, prevY, x, y, ringHalf, c);
				}
				prevX = x;
				prevY = y;
			}
			return;
		}
		boolean vertical = MARKER[M_VERTICAL] > 0f;
		int seg = r < 5 ? 6 : 12;
		int last = seg * 2 + 1;
		float prevX = 0f;
		float prevY = 0f;
		float firstX = 0f;
		float firstY = 0f;
		for (int i = 0; i <= last; i++) {
			// u 沿长轴、v 垂直于长轴；前半圈走一端（-90° → +90°），后半圈走另一端
			boolean second = i > seg;
			double ang = second
					? Math.PI / 2 + Math.PI * (i - seg - 1) / seg
					: -Math.PI / 2 + Math.PI * i / seg;
			float u = (second ? -len : len) + (float) Math.cos(ang) * r;
			float v = (float) Math.sin(ang) * r;
			float x = vertical ? cx + v : cx + u;
			float y = vertical ? cy + u : cy + v;
			if (i == 0) {
				firstX = x;
				firstY = y;
			} else {
				thickLine(vc, m, prevX, prevY, x, y, ringHalf, c);
			}
			prevX = x;
			prevY = y;
		}
		// 闭合：一端的终点连回另一端起点，正好补上另一条直边
		thickLine(vc, m, prevX, prevY, firstX, firstY, ringHalf, c);
	}

	/** 实心圆，用扇形四边形近似 */
	private static void circle(VertexConsumer vc, Matrix4f m, float cx, float cy, float radius, float[] c) {
		int seg = radius < 5 ? 8 : 16;
		for (int i = 0; i < seg; i++) {
			double a1 = i * 2 * Math.PI / seg;
			double a2 = (i + 1) * 2 * Math.PI / seg;
			quad(vc, m,
					cx, cy,
					cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius,
					cx + (float) Math.cos(a2) * radius, cy + (float) Math.sin(a2) * radius,
					cx, cy, c);
		}
	}

	private static void quad(VertexConsumer vc, Matrix4f m,
							 float x1, float y1, float x2, float y2,
							 float x3, float y3, float x4, float y4, float[] c) {
		// 1.21 起顶点 API 换成了 addVertex/setColor（旧版是 vertex/color + endVertex）
		//? if >=1.21.1 {
		/*vc.addVertex(m, x1, y1, 0f).setColor(c[0], c[1], c[2], c[3]);
		vc.addVertex(m, x2, y2, 0f).setColor(c[0], c[1], c[2], c[3]);
		vc.addVertex(m, x3, y3, 0f).setColor(c[0], c[1], c[2], c[3]);
		vc.addVertex(m, x4, y4, 0f).setColor(c[0], c[1], c[2], c[3]);
		*///?} else {
		vc.vertex(m, x1, y1, 0f).color(c[0], c[1], c[2], c[3]).endVertex();
		vc.vertex(m, x2, y2, 0f).color(c[0], c[1], c[2], c[3]).endVertex();
		vc.vertex(m, x3, y3, 0f).color(c[0], c[1], c[2], c[3]).endVertex();
		vc.vertex(m, x4, y4, 0f).color(c[0], c[1], c[2], c[3]).endVertex();
		//?}
	}

	/** MTR 颜色（ARGB 整数）转 RGBA 浮点数组；alpha 为 0 时按不透明处理 */
	private static float[] argb(int color, float alphaMul) {
		int a = (color >>> 24) & 0xFF;
		float af = (a == 0 ? 1f : a / 255f) * alphaMul;
		return new float[]{
				((color >> 16) & 0xFF) / 255f,
				((color >> 8) & 0xFF) / 255f,
				(color & 0xFF) / 255f,
				af
		};
	}

	private static float clamp(float v, float min, float max) {
		return v < min ? min : (v > max ? max : v);
	}
}
