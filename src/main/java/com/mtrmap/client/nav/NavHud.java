package com.mtrmap.client.nav;

import com.mtrmap.client.render.GuiSink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * 游戏内导航 HUD：把网页「路径查询」的结果精简成一块面板，贴在游戏窗口右上角，
 * 黑底白字，当前进行的任务用灰底高亮。
 *
 * <p>位置每帧按 {@code guiScaledWidth/Height} 重新计算，窗口移动、缩放（含全屏切换）
 * 时面板始终贴着窗口右上角。
 *
 * 动画：任务刚到时整块淡入；一步完成切到下一步时，灰色高亮条从上一行
 * 平滑滑到新的一行（260ms，easeOut），不会生硬跳变。
 *
 * 面板底部固定显示「Ctrl+x可以退出导航哦」；全部走完后显示
 * 「恭喜🎉任务已完成」，3 秒后自动收起。
 */
public final class NavHud {

    private static final int PANEL_W = 196;
    private static final int PAD = 6;
    private static final int ROW_H = 22;
    /** 距窗口边缘的留白（缩放前的逻辑像素） */
    private static final int MARGIN = 8;
    /** 整块面板的缩放系数：让导航窗口比原来小一圈 */
    private static final float SCALE = 0.78f;
    /** 高亮切换动画时长（毫秒） */
    private static final float ANIM_MS = 260f;

    private static final int BG = 0xE6000000;
    private static final int BORDER = 0xFF3A3A3A;
    private static final int TITLE = 0xFFFFFFFF;
    private static final int DIM = 0xFFB8B8B8;
    private static final int FOOTER = 0xFF9A9A9A;
    private static final int HIGHLIGHT = 0xFF555555;
    private static final int ACCENT = 0xFF7EC8E3;

    /** 用于高亮条的行间滑动动画 */
    private static int shownIndex = -1;
    private static int fromIndex = -1;
    private static long animStart;
    /** 面板整块淡入的起点（任务到达时间） */
    private static long fadeStart;

    private NavHud() {
    }

    public static void render(GuiSink sink) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.hideGui) {
            return;
        }
        Font font = mc.font;
        int guiW = mc.getWindow().getGuiScaledWidth();
        int guiH = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();

        // 完成祝贺：居中偏上，3 秒后消失
        if (NavController.isFinished()) {
            long dt = now - NavController.getFinishedAt();
            if (dt > NavController.DONE_TOAST_MS) {
                return;
            }
            drawCenteredToast(sink, font, guiW, guiH, dt);
            return;
        }

        NavTask task = NavController.getTask();
        if (task == null) {
            shownIndex = -1;
            fromIndex = -1;
            return;
        }

        int idx = NavController.getStepIndex();
        if (idx != shownIndex) {
            fromIndex = shownIndex;
            shownIndex = idx;
            animStart = now;
        }
        if (fadeStart != NavController.getTaskArrivedAt()) {
            fadeStart = NavController.getTaskArrivedAt();
        }

        float titleAlpha = clamp((now - fadeStart) / 200f, 0f, 1f);

        int stepCount = task.steps.length;
        int headLines = 3;                                  // 标题 + 起终点 + 概要
        int contentH = headLines * 10 + stepCount * ROW_H + 14;
        int panelH = contentH + PAD * 2;

        sink.push();
        sink.translate(0f, 0f, 300f);
        // 面板整体按逻辑像素布局，最后统一缩放；窗口尺寸每帧都会读到，
        // 所以窗口一动（缩放/全屏）面板就跟着贴回右上角。
        sink.scale(SCALE);
        int logicalW = Math.round(guiW / SCALE);
        int logicalH = Math.round(guiH / SCALE);
        int x = logicalW - PANEL_W - MARGIN;
        int y = MARGIN;
        if (y + panelH > logicalH - MARGIN) {
            y = Math.max(MARGIN, logicalH - panelH - MARGIN);
        }

        // 面板底 + 边框
        sink.fill(x, y, x + PANEL_W, y + panelH, applyAlpha(BG, titleAlpha));
        border(sink, x, y, PANEL_W, panelH, applyAlpha(BORDER, titleAlpha));

        int tx = x + PAD;
        int innerW = PANEL_W - PAD * 2 - 4;
        int cy = y + PAD;

        // 标题
        text(sink, font, "导航中", tx, cy, applyAlpha(TITLE, titleAlpha));
        cy += 10;

        // 起终点
        String route = fit(font, task.fromName + " → " + task.toName, innerW);
        text(sink, font, route, tx, cy, applyAlpha(ACCENT, titleAlpha));
        cy += 10;

        // 概要
        String summary = fit(font,
                "全程 " + fmtDist(task.dist) + " · 约 " + fmtMin(task.timeSec) + " · 换乘 " + task.transfers,
                innerW);
        text(sink, font, summary, tx, cy, applyAlpha(DIM, titleAlpha));
        cy += 12;

        // 步骤列表
        float p = clamp((now - animStart) / ANIM_MS, 0f, 1f);
        float ease = 1f - (1f - p) * (1f - p);
        int firstRowY = cy;
        if (fromIndex >= 0 && fromIndex != idx && fromIndex < stepCount) {
            float hy = lerp(firstRowY + fromIndex * ROW_H, firstRowY + idx * ROW_H, ease);
            sink.fill(x + 3, (int) hy, x + PANEL_W - 3, (int) hy + ROW_H - 2, applyAlpha(HIGHLIGHT, ease));
        } else if (fromIndex < 0 || fromIndex == idx) {
            sink.fill(x + 3, firstRowY + idx * ROW_H, x + PANEL_W - 3,
                    firstRowY + idx * ROW_H + ROW_H - 2, applyAlpha(HIGHLIGHT, titleAlpha));
        }

        for (int i = 0; i < stepCount; i++) {
            NavTask.Step s = task.steps[i];
            int ry = firstRowY + i * ROW_H;
            int c = i == idx ? TITLE : applyAlpha(DIM, 0.75f);
            // 线路色块（乘车步骤）
            int sx = tx;
            if (!s.walk) {
                sink.fill(sx, ry + 1, sx + 3, ry + 11, applyAlpha(opaque(s.color), titleAlpha));
                sx += 7;
            }
            String line1;
            String line2;
            if (s.walk) {
                line1 = "步行 " + fmtDist(s.distance) + (s.dir.isEmpty() ? "" : " 往" + s.dir);
                line2 = "到 " + s.to;
            } else {
                line1 = s.line + " 开往 " + s.towards;
                line2 = s.rideCount + " 站 → " + s.to;
            }
            text(sink, font, fit(font, line1, innerW - (sx - tx)), sx, ry + 1, c);
            text(sink, font, fit(font, line2, innerW - (sx - tx)), sx, ry + 11, applyAlpha(DIM, 0.85f));
        }

        // 底部提示：Ctrl+x可以退出导航哦
        String footer = fit(font, "Ctrl+x可以退出导航哦", innerW);
        text(sink, font, footer, tx, firstRowY + stepCount * ROW_H + 2, applyAlpha(FOOTER, titleAlpha));

        sink.pop();
    }

    /** 完成祝贺横幅 */
    private static void drawCenteredToast(GuiSink sink, Font font, int guiW, int guiH, long dt) {
        float alpha = dt > NavController.DONE_TOAST_MS - 500L
                ? clamp((NavController.DONE_TOAST_MS - dt) / 500f, 0f, 1f)
                : 1f;
        String msg = "恭喜🎉任务已完成";
        int w = font.width(msg) + 28;
        int h = 24;
        int x = (guiW - w) / 2;
        int y = guiH / 4;
        sink.push();
        sink.translate(0f, 0f, 400f);
        sink.fill(x, y, x + w, y + h, applyAlpha(BG, alpha));
        border(sink, x, y, w, h, applyAlpha(ACCENT, alpha));
        sink.text(font, msg, x + w / 2f, y + (h - 8) / 2f, applyAlpha(TITLE, alpha), true);
        sink.pop();
    }

    // ===== 小工具 =====

    private static void border(GuiSink sink, int x, int y, int w, int h, int color) {
        sink.fill(x, y, x + w, y + 1, color);
        sink.fill(x, y + h - 1, x + w, y + h, color);
        sink.fill(x, y, x + 1, y + h, color);
        sink.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** 左对齐文字：GuiSink.text 居中，这里换算成中心坐标 */
    private static void text(GuiSink sink, Font font, String s, int left, int y, int color) {
        sink.text(font, s, left + font.width(s) / 2f, y, color, true);
    }

    /** 超宽时截断并加省略号 */
    private static String fit(Font font, String s, int maxW) {
        if (s == null) {
            return "";
        }
        if (font.width(s) <= maxW) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            String next = sb.toString() + s.charAt(i);
            if (font.width(next + "…") > maxW) {
                break;
            }
            sb.append(s.charAt(i));
        }
        return sb + "…";
    }

    private static String fmtDist(double meters) {
        if (meters >= 1000) {
            return String.format("%.1fkm", meters / 1000.0);
        }
        return Math.round(meters) + "m";
    }

    private static String fmtMin(double sec) {
        int m = (int) Math.round(sec / 60.0);
        if (m < 1) {
            m = 1;
        }
        return m + "分钟";
    }

    private static int opaque(int argb) {
        return (argb & 0x00FFFFFF) | 0xFF000000;
    }

    private static int applyAlpha(int argb, float alpha) {
        int a = (argb >>> 24) & 0xFF;
        int na = Math.round(a * clamp(alpha, 0f, 1f));
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
