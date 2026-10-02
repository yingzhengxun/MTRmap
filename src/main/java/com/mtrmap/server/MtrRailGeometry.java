package com.mtrmap.server;

/**
 * 一条轨道的几何取样接口。
 *
 * MTR 3.x 与 4.x 的 {@code Rail} 是完全不同的两个类（包名、取长和取点的方法都不一样），
 * 而 {@link RailPathFinder} 只关心「这条轨道有多长」「走 d 格之后在哪」，所以在这里
 * 收成一个小接口，让寻路算法不必知道自己在跟哪个版本的 MTR 打交道。
 *
 * 实现由 {@link MtrNetwork} 在各版本的适配分支里给出。
 */
public interface MtrRailGeometry {

	/** 轨道长度（格） */
	double length();

	/** 沿轨道从起点走 {@code distance} 格之后的水平坐标 {@code [x, z]} */
	double[] position(double distance);
}
