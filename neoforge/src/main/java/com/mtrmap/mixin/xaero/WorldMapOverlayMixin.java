package com.mtrmap.mixin.xaero;

import com.mtrmap.client.nav.NavController;
import com.mtrmap.client.xaero.GuiSink;
import com.mtrmap.client.xaero.OverlayData;
import com.mtrmap.client.xaero.OverlayRenderer;
import com.mtrmap.config.MtrMapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;

import java.lang.reflect.Field;

/**
 * Xaero 世界地图叠加层（NeoForge 版）：把 MTR 线网画在世界地图之上，并在右侧提供两个开关
 * （线网总开关、车厂及其出入库线路开关）。
 *
 * 【与 Forge 版的差异：方法描述符用官方名，而不是 SRG】
 * NeoForge 1.20.2 起运行时使用 Mojang 官方映射（不再有 SRG 名字），
 * 因此 Xaero 的 NeoForge 产物里，GuiMap 重写 Minecraft Screen 的方法保留官方名与描述符：
 *   - render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V
 *   - mouseClicked(DDI)Z
 * （两者均对 xaero-neoforge-1.21.1.jar 经 javap 核实。）
 *
 * 即便如此仍要 remap = false：Mixin 的 refmap 只能翻译"Minecraft 自己的成员"，
 * 翻译不了"别的 mod 类上重写 MC 方法"的情况，所以这里直接按运行时字面量查找。
 *
 * 【坐标换算】GuiMap 的字段单位（均经字节码核实，NeoForge 产物与 Forge/Fabric 一致）：
 *   cameraX / cameraZ —— 屏幕正中心所对应的 map 坐标（世界坐标 ÷ 维度比例）
 *   scale             —— 每 1 个 map 单位对应的**物理像素**数
 * 而 GUI 用的是缩放后坐标，所以要再除以 guiScale：
 *   guiX = (worldX - cameraX) * (scale / guiScale) + guiScaledWidth / 2
 *
 * 【为什么用反射而不是 @Shadow】
 * 这是软联动，Xaero 版本变化时字段名可能变动。用反射可以在字段缺失时
 * 安静地不画，而 @Shadow 找不到字段会直接让游戏崩溃。
 *
 * 【可选依赖】本 mixin 是否生效由 XaeroMixinPlugin 决定：没装 Xaero 世界地图时整个 mixin 不会被应用。
 */
@Mixin(GuiMap.class)
public class WorldMapOverlayMixin {

	private static final Field CAMERA_X = findField("cameraX");
	private static final Field CAMERA_Z = findField("cameraZ");
	private static final Field MAP_SCALE = findField("scale");

	/** 右侧开关按钮的尺寸（紧凑一些，避免遮挡地图） */
	private static final float BUTTON_W = 76f;
	private static final float BUTTON_H = 18f;
	/** 两个按钮之间的间距 */
	private static final float BUTTON_GAP = 4f;

	private static Field findField(String name) {
		try {
			Field f = Class.forName("xaero.map.gui.GuiMap").getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (Throwable t) {
			return null;
		}
	}

	private static float buttonX(int guiW) {
		return guiW - BUTTON_W - 10f;
	}

	/** 线网开关按钮的 y */
	private static float linesButtonY(int guiH) {
		return guiH / 2f - BUTTON_H - BUTTON_GAP / 2f;
	}

	/** 车厂开关按钮的 y */
	private static float depotButtonY(int guiH) {
		return guiH / 2f + BUTTON_GAP / 2f;
	}

	private static boolean hit(float x, float y, float w, float h, double mx, double my) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	/** 读取相机坐标与缩放；任一字段缺失或异常都返回 null，调用方直接跳过绘制 */
	private double[] readView() {
		try {
			double sc = MAP_SCALE.getDouble(this);
			if (!(sc > 0) || !Double.isFinite(sc)) {
				return null;
			}
			return new double[]{CAMERA_X.getDouble(this), CAMERA_Z.getDouble(this), sc};
		} catch (Throwable t) {
			return null;
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), remap = false, require = 0)
	private void mtrmap$renderOverlay(GuiGraphics gg, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		mtrmap$drawOverlay(new GuiSink(gg), mouseX, mouseY);
	}

	/** 叠加层主体：开关按钮 + 线网绘制，与 Minecraft 版本无关 */
	private void mtrmap$drawOverlay(GuiSink sink, int mouseX, int mouseY) {
		if (CAMERA_X == null || CAMERA_Z == null || MAP_SCALE == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int guiW = mc.getWindow().getGuiScaledWidth();
		int guiH = mc.getWindow().getGuiScaledHeight();
		boolean showLines = MtrMapConfig.isXaeroOverlay();
		boolean showDepots = MtrMapConfig.isShowDepots();

		// 两个开关始终可见（关掉线网后也要能再打开）
		float bx = buttonX(guiW);
		drawToggleButton(sink, bx, linesButtonY(guiH), "mtrmap.xaero.on", "mtrmap.xaero.off",
				showLines, mouseX, mouseY);
		drawToggleButton(sink, bx, depotButtonY(guiH), "mtrmap.xaero.depot.on", "mtrmap.xaero.depot.off",
				showDepots, mouseX, mouseY);
		// 导航目标 waypoint：与线网开关无关，只要有导航任务就显示
		mtrmap$drawNavWaypoint(sink, guiW, guiH);
		if (!showLines) {
			return;
		}

		OverlayData.Snapshot snap = OverlayData.get();
		if (snap == null || (snap.stations.length == 0 && snap.lines.length == 0)) {
			return;
		}
		double[] view = readView();
		if (view == null) {
			return;
		}
		final double camX = view[0];
		final double camZ = view[1];
		// scale 是物理像素/方块，换算成 GUI 缩放坐标下的每方块像素数
		final double guiScale = mc.getWindow().getGuiScale();
		final double ppb = view[2] / (guiScale > 0 ? guiScale : 1.0);
		if (!(ppb > 0) || !Double.isFinite(ppb)) {
			return;
		}

		sink.push();
		// 抬高 z，确保压在地图内容与 Xaero 自身控件之上
		sink.translate(0f, 0f, 900f);
		OverlayRenderer.draw(sink, new OverlayRenderer.Projection() {
			@Override
			public float sx(double worldX, double worldZ) {
				return (float) ((worldX - camX) * ppb + guiW / 2.0);
			}

			@Override
			public float sz(double worldX, double worldZ) {
				return (float) ((worldZ - camZ) * ppb + guiH / 2.0);
			}

			@Override
			public float pxPerBlock() {
				return (float) ppb;
			}
		}, snap, 0f, 0f, guiW, guiH, showDepots);
		sink.pop();
	}

	@Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void mtrmap$onMouseClicked(double mouseX, double mouseY, int button,
									   CallbackInfoReturnable<Boolean> cir) {
		if (button != 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int guiW = mc.getWindow().getGuiScaledWidth();
		int guiH = mc.getWindow().getGuiScaledHeight();
		float bx = buttonX(guiW);
		if (hit(bx, linesButtonY(guiH), BUTTON_W, BUTTON_H, mouseX, mouseY)) {
			MtrMapConfig.setXaeroOverlay(!MtrMapConfig.isXaeroOverlay());
			MtrMapConfig.save();
		} else if (hit(bx, depotButtonY(guiH), BUTTON_W, BUTTON_H, mouseX, mouseY)) {
			MtrMapConfig.setShowDepots(!MtrMapConfig.isShowDepots());
			MtrMapConfig.save();
		} else {
			return;
		}
		// 消费掉这次点击，避免同时触发地图拖拽
		cir.setReturnValue(true);
	}

	/**
	 * 绘制一个开关按钮。
	 *
	 * @param onKey  开启状态的翻译键
	 * @param offKey 关闭状态的翻译键
	 */
	private static void drawToggleButton(GuiSink sink, float x, float y, String onKey, String offKey,
										 boolean on, int mouseX, int mouseY) {
		boolean hover = hit(x, y, BUTTON_W, BUTTON_H, mouseX, mouseY);
		int border = on ? 0xFF7EC8E3 : 0xFF9A9A9A;
		int bg = on ? (hover ? 0xF0205A44 : 0xD0184433) : (hover ? 0xF05A3030 : 0xD0402828);

		int ix = (int) x;
		int iy = (int) y;
		int iw = (int) BUTTON_W;
		int ih = (int) BUTTON_H;
		sink.fill(ix, iy, ix + iw, iy + ih, bg);
		// 1 像素边框
		sink.fill(ix, iy, ix + iw, iy + 1, border);
		sink.fill(ix, iy + ih - 1, ix + iw, iy + ih, border);
		sink.fill(ix, iy, ix + 1, iy + ih, border);
		sink.fill(ix + iw - 1, iy, ix + iw, iy + ih, border);

		Font font = Minecraft.getInstance().font;
		String label = Component.translatable(on ? onKey : offKey).getString();
		sink.text(font, label, ix + iw / 2f, iy + (ih - 8) / 2f, 0xFFFFFFFF, true);
	}

	// ===== 导航 waypoint =====

	/**
	 * 在世界地图上标出当前导航目标（当前步骤要去的地铁站中心）。
	 * 只要网页下发过导航任务就显示，和线网 / 车厂开关无关。
	 */
	private void mtrmap$drawNavWaypoint(GuiSink sink, int guiW, int guiH) {
		double[] wp = NavController.getWaypoint();
		if (wp == null) {
			return;
		}
		double[] view = readView();
		if (view == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		double guiScale = mc.getWindow().getGuiScale();
		double ppb = view[2] / (guiScale > 0 ? guiScale : 1.0);
		if (!(ppb > 0) || !Double.isFinite(ppb)) {
			return;
		}
		float px = (float) ((wp[0] - view[0]) * ppb + guiW / 2.0);
		float py = (float) ((wp[1] - view[1]) * ppb + guiH / 2.0);
		if (px < 0f || py < 0f || px > guiW || py > guiH) {
			return;
		}
		drawNavPin(sink, px, py);
	}

	/** 旗子形状的导航标记 + 目标标签 */
	private static void drawNavPin(GuiSink sink, float px, float py) {
		int cx = Math.round(px);
		int cy = Math.round(py);
		sink.push();
		sink.translate(0f, 0f, 950f);
		// 旗杆
		sink.fill(cx - 1, cy - 16, cx + 1, cy, 0xFFFFFFFF);
		// 旗面
		sink.fill(cx + 1, cy - 16, cx + 11, cy - 8, 0xFFFF5555);
		sink.fill(cx + 1, cy - 16, cx + 11, cy - 15, 0xFFFFFFFF);
		// 落点
		sink.fill(cx - 3, cy - 3, cx + 3, cy + 3, 0xFF7EC8E3);
		sink.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
		sink.pop();
		// 标签
		Font font = Minecraft.getInstance().font;
		String label = "导航目标";
		int w = font.width(label) + 8;
		int lx = cx - w / 2;
		int ly = cy + 5;
		sink.push();
		sink.translate(0f, 0f, 950f);
		sink.fill(lx, ly, lx + w, ly + 12, 0xE6000000);
		sink.fill(lx, ly, lx + w, ly + 1, 0xFF7EC8E3);
		sink.fill(lx, ly + 11, lx + w, ly + 12, 0xFF7EC8E3);
		sink.fill(lx, ly, lx + 1, ly + 12, 0xFF7EC8E3);
		sink.fill(lx + w - 1, ly, lx + w, ly + 12, 0xFF7EC8E3);
		sink.text(font, label, lx + w / 2f, ly + 2, 0xFFFFFFFF, true);
		sink.pop();
	}
}
