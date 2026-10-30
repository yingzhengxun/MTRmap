package com.mtrmap.client;

import com.mtrmap.config.MtrMapConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 模组设置界面。
 *
 * 既可以从模组菜单（Forge / NeoForge 的 mod 列表「配置」按钮）打开，
 * 也可以从游戏内地图窗口右下角的「选项」按钮打开，热键「打开设置」同样指向这里。
 * 所有开关都直接写回 mtrmap 的配置文件。
 */
public class SettingsScreen extends Screen {

    private final Screen parent;
    private Button ttsButton;
    private Button depotButton;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("key.rbmm.settings_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = height / 2 - 40;

        ttsButton = addRenderableWidget(Button.builder(ttsLabel(), b -> {
            MtrMapConfig.setTtsEnabled(!MtrMapConfig.isTtsEnabled());
            MtrMapConfig.save();
            b.setMessage(ttsLabel());
        }).bounds(cx - 110, y, 220, 20).build());

        depotButton = addRenderableWidget(Button.builder(depotLabel(), b -> {
            MtrMapConfig.setShowDepots(!MtrMapConfig.isShowDepots());
            MtrMapConfig.save();
            b.setMessage(depotLabel());
            if (minecraft != null && minecraft.screen instanceof com.mtrmap.client.map.MapScreen) {
                com.mtrmap.client.map.MapDataClient.requestRefresh();
            }
        }).bounds(cx - 110, y + 26, 220, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(cx - 110, y + 74, 220, 20).build());
    }

    private static Component ttsLabel() {
        return Component.translatable(MtrMapConfig.isTtsEnabled()
                ? "key.rbmm.tts_on" : "key.rbmm.tts_off");
    }

    private static Component depotLabel() {
        return Component.translatable(MtrMapConfig.isShowDepots()
                ? "key.rbmm.depot_on" : "key.rbmm.depot_off");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0101018);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 70, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("key.rbmm.settings_hint"),
                width / 2, height / 2 + 40, 0x9AA4B2);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}