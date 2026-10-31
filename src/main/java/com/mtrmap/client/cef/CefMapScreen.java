package com.mtrmap.client.cef;

import com.mtrmap.client.map.MapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 游戏内地图窗口（F6，CEF 版）。
 *
 * <p>整屏显示服务端提供的网页地图（与浏览器里看到的一模一样），
 * 鼠标、滚轮、键盘全部转发给网页；Esc 关闭窗口（关闭时浏览器保留，下次打开秒开）。
 *
 * <p>网页内核不可用时自动退回 {@link MapScreen}（Java 自绘版）。
 */
public class CefMapScreen extends Screen {

    private boolean pressed;
    private int pressedButton;

    public CefMapScreen() {
        super(Component.literal("MTR 地图"));
    }

    @Override
    protected void init() {
        super.init();
        CefMap.ensure(pixelWidth(), pixelHeight());
        CefMap.focus();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        CefMap.State state = CefMap.ensure(pixelWidth(), pixelHeight());
        if (state == CefMap.State.UNAVAILABLE) {
            // 网页内核起不来：换成 Java 自绘地图，功能不至于丢
            Minecraft.getInstance().setScreen(new MapScreen());
            return;
        }
        if (CefMap.textureId() <= 0) {
            graphics.fill(0, 0, width, height, 0xFF0B0F14);
            graphics.drawCenteredString(font, Component.literal("正在加载网页地图..."), width / 2, height / 2, 0xFFE0E0E0);
            return;
        }
        CefMap.draw(graphics, width, height);
    }

    @Override
    public boolean isPauseScreen() {
        // 单人游戏里打开地图不暂停世界（网页地图本来就要看到列车实时移动）
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    // ===== 输入转发 =====

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (CefMap.state() == CefMap.State.READY) {
            CefMap.mouseMove(pixelX(mouseX), pixelY(mouseY));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (CefMap.state() != CefMap.State.READY) {
            return false;
        }
        CefMap.mouseMove(pixelX(mouseX), pixelY(mouseY));
        CefMap.mousePress(pixelX(mouseX), pixelY(mouseY), button);
        CefMap.focus();
        pressed = true;
        pressedButton = button;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!pressed) {
            return false;
        }
        pressed = false;
        CefMap.mouseRelease(pixelX(mouseX), pixelY(mouseY), button);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (CefMap.state() != CefMap.State.READY) {
            return false;
        }
        // 网页里的拖拽（平移地图、拖动滑块）靠「按下 + 移动」自己处理
        CefMap.mouseMove(pixelX(mouseX), pixelY(mouseY));
        return true;
    }

    //? if >=1.21.1 {
    /*@Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (CefMap.state() != CefMap.State.READY) {
            return false;
        }
        CefMap.mouseWheel(pixelX(mouseX), pixelY(mouseY), scrollY, 0);
        return true;
    }
    *///?} else {
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (CefMap.state() != CefMap.State.READY) {
            return false;
        }
        CefMap.mouseWheel(pixelX(mouseX), pixelY(mouseY), delta, 0);
        return true;
    }
    //?}

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Esc 只负责关窗口，其它按键都交给网页（搜索框、对话框都要用键盘）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (CefMap.state() != CefMap.State.READY) {
            return false;
        }
        CefMap.keyPress(keyCode, scanCode, modifiers);
        CefMap.focus();
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (CefMap.state() != CefMap.State.READY) {
            return false;
        }
        CefMap.keyRelease(keyCode, scanCode, modifiers);
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (codePoint == 0 || CefMap.state() != CefMap.State.READY) {
            return false;
        }
        CefMap.keyTyped(codePoint, modifiers);
        CefMap.focus();
        return true;
    }

    // ===== 坐标换算：界面坐标 -> 浏览器像素坐标 =====

    private int pixelX(double guiX) {
        int widthPixels = pixelWidth();
        if (width <= 0 || widthPixels <= 0) {
            return 0;
        }
        return (int) Math.round(guiX * widthPixels / width);
    }

    private int pixelY(double guiY) {
        int heightPixels = pixelHeight();
        if (height <= 0 || heightPixels <= 0) {
            return 0;
        }
        return (int) Math.round(guiY * heightPixels / height);
    }

    private static int pixelWidth() {
        return Minecraft.getInstance().getWindow().getWidth();
    }

    private static int pixelHeight() {
        return Minecraft.getInstance().getWindow().getHeight();
    }
}