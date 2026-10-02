package com.mtrmap.mixin.hud;

import com.mtrmap.client.nav.NavHud;
import com.mtrmap.client.xaero.GuiSink;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.1 {
/*import net.minecraft.client.DeltaTracker;
*///?}
//? if >=1.20.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}

/**
 * 在 HUD 渲染末尾画导航面板（小地图旁边的黑底白字路线）。
 *
 * 注入 {@code Gui.render}——这是每帧绘制 HUD 的入口。
 * 描述符随版本变化，所以三端分开写：
 *   1.19.2 → render(PoseStack, float)
 *   1.20.1 → render(GuiGraphics, float)
 *   1.21.1 → render(GuiGraphics, DeltaTracker)
 * 用 require = 0 让不匹配的注入安静跳过；真正生效的只有当前版本那一条。
 *
 * 绘制本身走 {@link GuiSink}，与 Xaero 世界地图叠加层共用同一套版本无关封装。
 */
@Mixin(Gui.class)
public class GuiHudMixin {

	//? if <=1.19.2 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At("TAIL"), require = 0)
	private void mtrmap$renderNavHud(PoseStack pose, float partialTick, CallbackInfo ci) {
		NavHud.render(new GuiSink(pose));
	}
	*///?}
	//? if >=1.20.1 {
	//? if >=1.21.1 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"), require = 0)
	private void mtrmap$renderNavHud(GuiGraphics gg, DeltaTracker deltaTracker, CallbackInfo ci) {
		NavHud.render(new GuiSink(gg));
	}
	*///?} else {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At("TAIL"), require = 0)
	private void mtrmap$renderNavHud(GuiGraphics gg, float partialTick, CallbackInfo ci) {
		NavHud.render(new GuiSink(gg));
	}
	//?}
	//?}
}
