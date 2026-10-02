package com.mtrmap.client.nav;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 网页地图下发过来的导航任务（客户端只读快照）。
 *
 * 结构与网页「路径查询」结果一致但做了精简：一组按顺序执行的步骤
 * （步行 / 乘车），每个步骤都带目标地铁站的坐标，客户端据此判断
 * 当前做到哪一步、以及把 waypoint 指向哪里。
 */
public final class NavTask {

    /** 起点站 */
    public final String fromName;
    public final double fromX;
    public final double fromZ;
    /** 终点站 */
    public final String toName;
    public final double toX;
    public final double toZ;

    public final double dist;
    public final double timeSec;
    public final int transfers;
    public final long startedAt;

    /** 从「我的位置」步行到起点站的指引，起点不是用我的位置选的则为 null */
    public final StartWalk startWalk;

    public final Step[] steps;

    private NavTask(String fromName, double fromX, double fromZ,
                    String toName, double toX, double toZ,
                    double dist, double timeSec, int transfers, long startedAt,
                    StartWalk startWalk, Step[] steps) {
        this.fromName = fromName;
        this.fromX = fromX;
        this.fromZ = fromZ;
        this.toName = toName;
        this.toX = toX;
        this.toZ = toZ;
        this.dist = dist;
        this.timeSec = timeSec;
        this.transfers = transfers;
        this.startedAt = startedAt;
        this.startWalk = startWalk;
        this.steps = steps;
    }

    /** 从「我的位置」步行到起点站 */
    public static final class StartWalk {
        public final String dir;
        public final double distance;
        public final double x;
        public final double z;
        /** 起点站名 */
        public final String station;

        StartWalk(String dir, double distance, double x, double z, String station) {
            this.dir = dir;
            this.distance = distance;
            this.x = x;
            this.z = z;
            this.station = station;
        }
    }

    /** 一个执行步骤：Walk 或 Ride */
    public static final class Step {
        public final boolean walk;
        /** 目标地铁站：名称 + 坐标 */
        public final String to;
        public final double x;
        public final double z;
        /** 步行：距离与方位（东/南/西/北…） */
        public final double distance;
        public final String dir;
        /** 乘车：线路名、线路色（ARGB）、开往终点站、乘坐站数、上车站 */
        public final String line;
        public final int color;
        public final String towards;
        public final int rideCount;
        public final String from;

        private Step(boolean walk, String to, double x, double z, double distance, String dir,
                     String line, int color, String towards, int rideCount, String from) {
            this.walk = walk;
            this.to = to;
            this.x = x;
            this.z = z;
            this.distance = distance;
            this.dir = dir;
            this.line = line;
            this.color = color;
            this.towards = towards;
            this.rideCount = rideCount;
            this.from = from;
        }

        static Step walk(String to, double x, double z, double distance, String dir) {
            return new Step(true, to, x, z, distance, dir, null, 0, null, 0, null);
        }

        static Step ride(String line, int color, String towards, int rideCount, String from,
                         String to, double x, double z) {
            return new Step(false, to, x, z, 0, null, line, color, towards, rideCount, from);
        }
    }

    /** 服务端返回的 JSON 不是有效任务时返回 null */
    public static NavTask parse(JsonObject obj) {
        if (obj == null || !obj.has("steps")) {
            return null;
        }
        JsonObject from = obj.has("from") && obj.get("from").isJsonObject()
                ? obj.getAsJsonObject("from") : new JsonObject();
        JsonObject to = obj.has("to") && obj.get("to").isJsonObject()
                ? obj.getAsJsonObject("to") : new JsonObject();

        StartWalk startWalk = null;
        if (obj.has("startWalk") && obj.get("startWalk").isJsonObject()) {
            JsonObject sw = obj.getAsJsonObject("startWalk");
            startWalk = new StartWalk(str(sw, "dir"), num(sw, "distance"),
                    num(sw, "x"), num(sw, "z"), str(sw, "station"));
        }

        List<Step> steps = new ArrayList<>();
        JsonArray arr = obj.getAsJsonArray("steps");
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject s = e.getAsJsonObject();
            String type = str(s, "type");
            if ("walk".equals(type)) {
                steps.add(Step.walk(str(s, "to"), num(s, "x"), num(s, "z"),
                        num(s, "distance"), str(s, "dir")));
            } else if ("ride".equals(type)) {
                steps.add(Step.ride(str(s, "line"), (int) num(s, "color"), str(s, "towards"),
                        (int) num(s, "rideCount"), str(s, "from"),
                        str(s, "to"), num(s, "x"), num(s, "z")));
            }
        }
        if (steps.isEmpty()) {
            return null;
        }
        return new NavTask(
                str(from, "name"), num(from, "x"), num(from, "z"),
                str(to, "name"), num(to, "x"), num(to, "z"),
                num(obj, "dist"), num(obj, "timeSec"), (int) num(obj, "transfers"),
                (long) num(obj, "startedAt"),
                startWalk,
                steps.toArray(new Step[0]));
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static double num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
        } catch (Throwable t) {
            return 0;
        }
    }
}
