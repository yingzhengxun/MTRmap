package com.mtrmap.mixin;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Xaero 联动 Mixin 的加载开关（NeoForge 版）。
 *
 * Xaero 只是"软联动"，不是前置依赖：没装 Xaero 世界地图时，
 * 对应的 mixin 类根本不会被应用，因此也不会因为找不到目标类而报错。
 *
 * 【为什么用 ModList + LoadingModList 双路径】
 * Mixin 插件在非常早期的阶段（mixin 配置加载）就会执行，早于 mod 入口的初始化。
 * 此时 {@link ModList#get()} 可能尚未构建完成（会抛异常或返回 null），
 * 于是回退到更早可用的 {@link LoadingModList}。两条路径都 try/catch 兜底，任何情况下都不会崩溃。
 * 结果不缓存：每次判断都重新取，避免过早缓存"还没加载完"的错误结论。
 * （NeoForge 里这两个类的包名与 Forge 的不同：{@code net.neoforged.fml.*}。）
 */
public class XaeroMixinPlugin implements IMixinConfigPlugin {

	private static boolean isLoaded(String modId) {
		try {
			ModList modList = ModList.get();
			if (modList != null) {
				return modList.isLoaded(modId);
			}
		} catch (Throwable ignored) {
			// ModList 尚未就绪，走下面的兜底
		}
		try {
			LoadingModList loadingModList = LoadingModList.get();
			return loadingModList != null && loadingModList.getModFileById(modId) != null;
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
			return isLoaded("xaeroworldmap");
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
