package com.mtrmap.client.nav;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mtrmap.MtrMapCommon;
import com.mtrmap.client.MapChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;

/**
 * 游戏内导航控制器。
 *
 * <p>数据流：网页地图把路线 POST 到地图服务的 /api/nav（记在该玩家名下），
 * 客户端这里每秒向服务端领一次任务（服务端取走即删，天然一次性）。
 * 领取与回传都走模组网络包（{@link MapChannel}），不依赖地图 HTTP 服务。
 * 领取到任务后就地保存在本地，之后不再依赖网络；到达判定、Ctrl+X 退出、
 * 完成/中途退出后回传行程记录，全部在本类里完成。
 *
 * <p>进度判定：任务是一串带坐标的步骤，玩家走到当前步骤的目标站附近
 * （水平距离小于 {@link #ARRIVE_RADIUS}）就推进到下一步，走完即完成。
 */
public final class NavController {

    /** 轮询间隔：导航要跟手，1 秒一次 */
    private static final long POLL_INTERVAL_MS = 1000L;
    /** 到达当前步骤目标站的判定半径（方块） */
    private static final double ARRIVE_RADIUS = 16.0;
    /** 完成后「恭喜任务已完成」的展示时长（毫秒） */
    public static final long DONE_TOAST_MS = 3000L;

    private static volatile String playerUuid;
    private static volatile NavTask task;
    /** 当前任务的 rev：网页用它标识「同一条路线」，相同 rev 的重复下发只刷新展示信息 */
    private static volatile String taskRev;
    private static volatile int stepIndex;
    /** 任务刚收到的时间（用于切换动画） */
    private static volatile long taskArrivedAt;
    /** 当前步骤刚开始的时间（用于切换动画） */
    private static volatile long stepChangedAt;
    /** 全部步骤已完成，正在展示祝贺 */
    private static volatile boolean finished;
    private static volatile long finishedAt;
    /** 已经退出导航（Ctrl+X），不再显示面板 */
    private static volatile boolean stopped;

    private static volatile boolean threadStarted;
    private static boolean ctrlWasDown;

    private NavController() {
    }

    /** 客户端初始化：启动后台轮询线程（只启动一次） */
    public static void start() {
        if (threadStarted) {
            return;
        }
        threadStarted = true;
        Thread thread = new Thread(NavController::loop, "MTRMap-NavPoll");
        thread.setDaemon(true);
        thread.start();
    }

    /** 断开连接：清空当前任务与玩家标识 */
    public static void onDisconnect() {
        playerUuid = null;
        taskRev = null;
        clearTask();
    }

    private static void clearTask() {
        task = null;
        stepIndex = 0;
        finished = false;
        stopped = false;
    }

    // ===== 轮询 =====

    private static void loop() {
        while (true) {
            try {
                String uuid = playerUuid;
                if (uuid != null) {
                    JsonObject obj = fetchTask(uuid);
                    if (obj != null && obj.has("steps")) {
                        NavTask parsed = NavTask.parse(obj);
                        if (parsed != null) {
                            String rev = obj.has("rev") ? obj.get("rev").getAsString() : null;
                            if (rev != null && rev.equals(taskRev)) {
                                // 同一条路线：只刷新展示信息（例如步行距离），进度不动。
                                // 本地已经没有任务说明用户刚 Ctrl+X 退出或已完成这条路线，
                                // 此时忽略重复下发，否则退出后会被周期性重发又拉起来。
                                if (task != null) {
                                    task = parsed;
                                }
                            } else {
                                // 换了路线（换方案 / 换起终点）：整条替换并重新开始
                                taskRev = rev;
                                task = parsed;
                                stepIndex = 0;
                                finished = false;
                                stopped = false;
                                taskArrivedAt = System.currentTimeMillis();
                                stepChangedAt = taskArrivedAt;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                // 服务端未启动 / 端口未监听时静默重试
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static JsonObject fetchTask(String uuid) {
        String body = MapChannel.request("/api/nav?uuid=" + uuid, null);
        if (body == null) {
            return null;
        }
        JsonElement root = MtrMapCommon.parseJson(body);
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject obj = root.getAsJsonObject();
        // 服务端没有任务时返回空对象 {}
        return obj.size() == 0 ? null : obj;
    }

    // ===== 每 tick =====

    public static void onClientTick(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        playerUuid = mc.player.getUUID().toString();

        // Ctrl+X 退出导航（只在没有打开任何界面时响应）
        boolean ctrlDown = isDown(mc, GLFW.GLFW_KEY_LEFT_CONTROL) || isDown(mc, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean xDown = isDown(mc, GLFW.GLFW_KEY_X);
        boolean combo = ctrlDown && xDown;
        if (combo && !ctrlWasDown && mc.screen == null && task != null && !finished) {
            exitNavigation();
        }
        ctrlWasDown = combo;

        NavTask t = task;
        if (t == null) {
            return;
        }
        // 完成后展示 3 秒再收起
        if (finished) {
            if (System.currentTimeMillis() - finishedAt >= DONE_TOAST_MS) {
                clearTask();
            }
            return;
        }
        if (stopped) {
            return;
        }

        LocalPlayer player = mc.player;
        if (stepIndex >= t.steps.length) {
            finish(t);
            return;
        }
        NavTask.Step step = t.steps[stepIndex];
        double dx = player.getX() - step.x;
        double dz = player.getZ() - step.z;
        if (Math.sqrt(dx * dx + dz * dz) <= ARRIVE_RADIUS) {
            stepIndex++;
            stepChangedAt = System.currentTimeMillis();
            if (stepIndex >= t.steps.length) {
                finish(t);
            }
        }
    }

    private static boolean isDown(Minecraft mc, int key) {
        return InputConstants.isKeyDown(mc.getWindow().getWindow(), key);
    }

    /** 走完全部步骤：展示祝贺并回传一条「已完成」行程 */
    private static void finish(NavTask t) {
        if (finished) {
            return;
        }
        finished = true;
        finishedAt = System.currentTimeMillis();
        postTrip(t, "completed");
    }

    /** Ctrl+X：中断导航并回传一条「已停止」行程 */
    private static void exitNavigation() {
        NavTask t = task;
        stopped = true;
        if (t != null) {
            postTrip(t, "stopped");
        }
        // 保留 stopped 标记用于刷新面板，下一 tick 由面板读取；简单起见这里直接清空
        clearTask();
    }

    // ===== 行程回传 =====

    private static void postTrip(NavTask t, String status) {
        final String uuid = playerUuid;
        if (uuid == null) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("status", status);
        body.addProperty("dist", t.dist);
        body.addProperty("timeSec", t.timeSec);
        body.addProperty("transfers", t.transfers);
        body.addProperty("time", t.startedAt > 0 ? t.startedAt : System.currentTimeMillis() / 1000L);
        JsonObject from = new JsonObject();
        from.addProperty("name", t.fromName);
        from.addProperty("x", t.fromX);
        from.addProperty("z", t.fromZ);
        body.add("from", from);
        JsonObject to = new JsonObject();
        to.addProperty("name", t.toName);
        to.addProperty("x", t.toX);
        to.addProperty("z", t.toZ);
        body.add("to", to);

        final String json = body.toString();
        Thread thread = new Thread(() -> postJson(uuid, json), "MTRMap-NavTrip");
        thread.setDaemon(true);
        thread.start();
    }

    private static void postJson(String uuid, String json) {
        // 回传失败不影响游戏
        MapChannel.request("/api/trips", json);
    }

    // ===== 供 HUD / waypoint 读取 =====

    public static NavTask getTask() {
        return task;
    }

    public static int getStepIndex() {
        return stepIndex;
    }

    public static boolean isFinished() {
        return finished;
    }

    public static long getFinishedAt() {
        return finishedAt;
    }

    public static long getStepChangedAt() {
        return stepChangedAt;
    }

    public static long getTaskArrivedAt() {
        return taskArrivedAt;
    }

    /**
     * 当前导航要指向的目标坐标（waypoint）。
     * 取当前步骤的目标站；全部走完或没有任务时返回 null。
     */
    public static double[] getWaypoint() {
        NavTask t = task;
        if (t == null || finished) {
            return null;
        }
        int i = Math.min(stepIndex, t.steps.length - 1);
        if (i < 0) {
            return null;
        }
        NavTask.Step s = t.steps[i];
        return new double[]{s.x, s.z};
    }
}
