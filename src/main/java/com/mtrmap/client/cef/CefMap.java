package com.mtrmap.client.cef;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.cinemamod.mcef.MCEFRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.client.MapEndpoint;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * 游戏内网页地图的浏览器（CEF / MCEF）。
 *
 * <p>把服务端自己提供的网页（{@link MapEndpoint#pageUrl()}，也就是浏览器里看到的同一套页面）
 * 交给 MCEF 的离屏浏览器渲染成一张 OpenGL 纹理，再由 {@link CefMapScreen} 全屏贴出来。
 * 页面里的取数、路径查询、导航下发、彩蛋、行程记录都由网页自己跑，游戏这边只负责显示和输入转发。
 *
 * <p>浏览器对象只创建一次并复用：关掉地图再打开不会重新加载页面。
 * 网页内核没就绪时先返回 {@link State#LOADING}，初始化失败则返回 {@link State#UNAVAILABLE}，
 * 由调用方退回 Java 自绘地图。
 */
public final class CefMap {

    /** 网页内核状态 */
    public enum State {
        /** 正在初始化 / 等端口同步 */
        LOADING,
        /** 已可渲染 */
        READY,
        /** 不可用（初始化失败），调用方应退回 Java 地图 */
        UNAVAILABLE
    }

    /** 等待 MCEF 初始化的上限：超时就认为内核起不来，避免一直卡在加载页 */
    private static final long INIT_TIMEOUT_MS = 15000L;

    private static MCEFBrowser browser;
    private static State state = State.LOADING;
    private static boolean initRequested;
    private static long initStartedAt;
    private static int width;
    private static int height;

    private CefMap() {
    }

    public static synchronized State state() {
        return state;
    }

    /**
     * 确保浏览器已按给定像素尺寸创建。
     *
     * <p>只能在渲染线程调用（屏幕的 init / render 都是）。
     *
     * @param pixelWidth  当前窗口的像素宽（浏览器内部坐标系）
     * @param pixelHeight 当前窗口的像素高
     */
    public static synchronized State ensure(int pixelWidth, int pixelHeight) {
        if (state == State.UNAVAILABLE) {
            return state;
        }
        if (browser != null) {
            resize(pixelWidth, pixelHeight);
            return state;
        }
        if (!MCEF.isInitialized()) {
            requestInit();
            if (System.currentTimeMillis() - initStartedAt > INIT_TIMEOUT_MS) {
                MtrMapCommon.LOGGER.warn("网页内核初始化超时，改用 Java 地图");
                state = State.UNAVAILABLE;
            }
            return state;
        }
        String url = targetUrl();
        if (url == null) {
            // 服务端端口还没同步过来，下一帧再试
            return state;
        }
        try {
            MtrMapCommon.LOGGER.info("创建游戏内网页地图：{}", url);
            MCEFBrowser created = MCEF.createBrowser(url, false, pixelWidth, pixelHeight);
            if (created == null) {
                state = State.UNAVAILABLE;
                return state;
            }
            browser = created;
            width = pixelWidth;
            height = pixelHeight;
            state = State.READY;
        } catch (Throwable t) {
            MtrMapCommon.LOGGER.warn("创建网页浏览器失败，改用 Java 地图", t);
            browser = null;
            state = State.UNAVAILABLE;
        }
        return state;
    }

    /** 当前可用的纹理 ID；0 或负数表示还没画过任何一帧 */
    public static synchronized int textureId() {
        if (browser == null) {
            return -1;
        }
        MCEFRenderer renderer = browser.getRenderer();
        return renderer == null ? -1 : renderer.getTextureID();
    }

    /** 断开连接时丢掉浏览器：换服后端口可能不同，下次打开重新建 */
    public static synchronized void reset() {
        if (browser != null) {
            try {
                browser.close();
            } catch (Throwable t) {
                MtrMapCommon.LOGGER.warn("关闭网页浏览器失败", t);
            }
            browser = null;
        }
    }

    // ===== 渲染 =====

    /** 整屏贴出浏览器纹理 */
    public static void draw(GuiGraphics graphics, int screenWidth, int screenHeight) {
        int texture = textureId();
        if (texture <= 0) {
            return;
        }
        Matrix4f matrix = graphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, texture);
        //? if >=1.21.1 {
        /*BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex(matrix, 0, screenHeight, 0).setUv(0, 1).setColor(255, 255, 255, 255);
        buffer.addVertex(matrix, screenWidth, screenHeight, 0).setUv(1, 1).setColor(255, 255, 255, 255);
        buffer.addVertex(matrix, screenWidth, 0, 0).setUv(1, 0).setColor(255, 255, 255, 255);
        buffer.addVertex(matrix, 0, 0, 0).setUv(0, 0).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        *///?} else {
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.vertex(matrix, 0, screenHeight, 0).uv(0, 1).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, screenWidth, screenHeight, 0).uv(1, 1).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, screenWidth, 0, 0).uv(1, 0).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, 0, 0, 0).uv(0, 0).color(255, 255, 255, 255).endVertex();
        BufferUploader.drawWithShader(buffer.end());
        //?}
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    // ===== 输入转发 =====

    public static synchronized void focus() {
        if (browser == null) {
            return;
        }
        try {
            browser.setFocus(true);
        } catch (Throwable ignored) {
            // 浏览器已关闭时忽略
        }
    }

    public static synchronized void mouseMove(int x, int y) {
        if (browser == null) {
            return;
        }
        try {
            browser.sendMouseMove(x, y);
        } catch (Throwable ignored) {
            // 浏览器已关闭时忽略
        }
    }

    public static synchronized void mousePress(int x, int y, int button) {
        if (browser == null) {
            return;
        }
        try {
            browser.sendMousePress(x, y, button);
        } catch (Throwable ignored) {
            // 浏览器已关闭时忽略
        }
    }

    public static synchronized void mouseRelease(int x, int y, int button) {
        if (browser == null) {
            return;
        }
        try {
            browser.sendMouseRelease(x, y, button);
        } catch (Throwable ignored) {
            // 浏览器已关闭时忽略
        }
    }

    public static synchronized void mouseWheel(int x, int y, double delta, int modifiers) {
        if (browser == null) {
            return;
        }
        try {
            browser.sendMouseWheel(x, y, delta, modifiers);
        } catch (Throwable ignored) {
            // 浏览器已关闭时忽略
        }
    }

    public static synchronized void keyPress(int keyCode, int scanCode, int modifiers) {
        if (browser == null) {
            return;
        }
        try {
            browser.sendKeyPress(keyCode, scanCode, modifiers);
        } catch (Throwable ignored) {
            // 浏览器已关闭时忽略
        }
    }

    public static synchronized void keyRelease(int keyCode, int scanCode, int modifiers) {
        if (browser == null) {
            return;
        }
        try {
            browser.sendKeyRelease(keyCode, scanCode, modifiers);
        } catch (Throwable ignored) {
            // 浏览器已关闭时忽略
        }
    }

    public static synchronized void keyTyped(char codePoint, int modifiers) {
        if (browser == null) {
            return;
        }
        try {
            browser.sendKeyTyped(codePoint, modifiers);
        } catch (Throwable ignored) {
            // 浏览器已关闭时忽略
        }
    }

    // ===== 内部 =====

    private static void resize(int pixelWidth, int pixelHeight) {
        if (pixelWidth <= 0 || pixelHeight <= 0) {
            return;
        }
        if (pixelWidth == width && pixelHeight == height) {
            return;
        }
        width = pixelWidth;
        height = pixelHeight;
        resizeBrowser();
    }

    private static void resizeBrowser() {
        if (browser == null) {
            return;
        }
        try {
            browser.resize(width, height);
        } catch (Throwable t) {
            MtrMapCommon.LOGGER.warn("调整网页浏览器尺寸失败", t);
        }
    }

    private static void requestInit() {
        if (initRequested) {
            return;
        }
        initRequested = true;
        initStartedAt = System.currentTimeMillis();
        try {
            // MCEF 自己会在客户端启动时开始初始化，这里只登记回调拿到结果
            MCEF.scheduleForInit(ok -> {
                if (!ok) {
                    MtrMapCommon.LOGGER.warn("网页内核初始化失败，改用 Java 地图");
                    state = State.UNAVAILABLE;
                }
            });
        } catch (Throwable t) {
            MtrMapCommon.LOGGER.warn("登记网页内核初始化回调失败，改用 Java 地图", t);
            state = State.UNAVAILABLE;
        }
    }

    /** 网页地址；端口还没同步时返回 null */
    private static String targetUrl() {
        int port = MtrMapCommon.getMapPort();
        if (port <= 0) {
            return null;
        }
        return MapEndpoint.pageUrl();
    }
}
