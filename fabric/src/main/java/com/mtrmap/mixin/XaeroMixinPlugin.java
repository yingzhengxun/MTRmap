package com.mtrmap.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Xaero 联动 Mixin 的加载开关。
 *
 * Xaero 只是"软联动"，不是前置依赖：没装 Xaero 世界地图时，
 * 对应的 mixin 类根本不会被应用，因此也不会因为找不到目标类而报错。
 *
 * 【为什么这里直接用 FabricLoader 而不是 Platform】
 * Mixin 插件在非常早期的阶段（mixin 配置加载）就会执行，早于 mod 入口的初始化，
 * 那时 Platform 尚未注入。本类只存在于 fabric 模块，直接用 FabricLoader 最稳。
 */
public class XaeroMixinPlugin implements IMixinConfigPlugin {

	private static final boolean WORLD_MAP = isLoaded("xaeroworldmap");

	private static boolean isLoaded(String modId) {
		try {
			return FabricLoader.getInstance().isModLoaded(modId);
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith("WorldMapOverlayMixin")) {
			return WORLD_MAP;
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
