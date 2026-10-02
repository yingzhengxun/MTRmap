package com.mtrmap.mixin;

import com.mtrmap.MtrMapCommon;
import com.mtrmap.config.MtrMapConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URI;

/**
 * 混入 Screen 类，为所有 DashboardScreen 实例在 init 末尾添加"交通线路图"按钮。
 * 之所以混入 Screen 而非 DashboardScreen，是因为 @Shadow 无法在外部 jar 的类
 * 中找到继承自 Screen 的成员（width/height）。
 */
@Mixin(Screen.class)
public class DashboardScreenMixin {

	@Shadow
	protected int width;

	@Shadow
	protected int height;

	//? if >=1.20.1 {
	@Shadow
	protected <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable> T addRenderableWidget(T widget) {
		return null;
	}
	//?} else if >=1.17 {
	/*@Shadow
	protected <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Widget> T addRenderableWidget(T widget) {
		return null;
	}
	*///?} else {
	/*// 1.16.5 还没有 addRenderableWidget，按钮一律走 Screen.addButton
	@Shadow
	protected <T extends AbstractWidget> T addButton(T widget) {
		return null;
	}
	*///?}

	@Inject(method = "init()V", at = @At("TAIL"))
	private void onInit(CallbackInfo ci) {
		// 仅对 MTR 铁路仪表板生效（用类名判断，原因见 isDashboardScreen）
		if (!isDashboardScreen(this)) {
			return;
		}
		int port = MtrMapConfig.getPort();
		Button.OnPress onPress = button -> {
			try {
				Util.getPlatform().openUri(new URI("http://localhost:" + port));
			} catch (Exception e) {
				MtrMapCommon.LOGGER.error("打开浏览器失败", e);
			}
		};
		// 1.19.2 还没有 Button.Builder，只能用构造器
		//? if >=1.20.1 {
		Button mapButton = Button.builder(translatable("key.mtrmap.map_button"), onPress)
				.bounds(width - 300, height - 20, 100, 20).build();
		//?} else {
		/*Button mapButton = new Button(width - 300, height - 20, 100, 20,
				translatable("key.mtrmap.map_button"), onPress);
		*///?}
		//? if >=1.17 {
		addRenderableWidget(mapButton);
		//?} else {
		/*addButton(mapButton);
		*///?}
	}

	/** {@code Component.translatable} 是 MC 1.19 才加的，1.18.2 与 1.16.5 只能用 TranslatableComponent。 */
	//? if >=1.19 {
	private static Component translatable(String key) {
		return Component.translatable(key);
	}
	//?} else {
	/*private static Component translatable(String key) {
		return new net.minecraft.network.chat.TranslatableComponent(key);
	}
	*///?}

	/**
	 * 判断某个 Screen 是否是 MTR 的铁路仪表板（或其子类）。
	 *
	 * 这里用类名字符串沿继承链比对，而不是 {@code instanceof DashboardScreen}：
	 * mtr.screen.DashboardScreen 位于外部 jar，属于"软引用"。若运行时装的 MTR 分支
	 * 不含该类（或被替换成包名不同的 fork），{@code instanceof} 会在触发类解析时抛出
	 * {@link NoClassDefFoundError}，进而拖垮整个 Screen.init。
	 * 字符串比较完全不触发目标类加载，天然安全；同时沿父类链遍历，保留 instanceof 对子类同样成立
	 * 的语义，因此对现有可用环境行为完全不变。官方 MTR 与 Yomi fork 都用同一个类名。
	 *
	 * 两个类名都要认：MTR 3.x（含 Yomi fork）用 mtr.screen.DashboardScreen，
	 * MTR 4.x 换到了 org.mtr.mod.screen.DashboardScreen。
	 */
	private static boolean isDashboardScreen(Object screen) {
		for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
			String name = c.getName();
			if ("mtr.screen.DashboardScreen".equals(name) || "org.mtr.mod.screen.DashboardScreen".equals(name)) {
				return true;
			}
		}
		return false;
	}
}
