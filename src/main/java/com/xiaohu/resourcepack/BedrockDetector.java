package com.xiaohu.resourcepack;

import java.util.UUID;

/**
 * 软依赖的"基岩版玩家"检测（互通服：Geyser / Floodgate）。
 * <p>
 * 用反射调用 Geyser / Floodgate 的 API（类名与方法是长期稳定接口），服务器装有互通插件时
 * 正确识别基岩版玩家；未安装时静默返回 false，仅 Java 版玩家会收到资源包。
 * 不引入任何硬编码 maven 依赖，避免纯 Java 服加载失败。
 */
public final class BedrockDetector {

    private BedrockDetector() {
        // util class
    }

    /**
     * 判断玩家是否为基岩版（经 Geyser / Floodgate 进入的互通玩家）。
     * 任一口径命中即返回 true。
     */
    public static boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return isGeyserBedrock(uuid) || isFloodgateBedrock(uuid);
    }

    private static boolean isGeyserBedrock(UUID uuid) {
        try {
            Class<?> geyserApi = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = geyserApi.getMethod("api").invoke(null);
            return Boolean.TRUE.equals(geyserApi.getMethod("isBedrockPlayer", UUID.class).invoke(api, uuid));
        } catch (Throwable t) {
            // Geyser 未安装或 API 版本不匹配——不影响 Java 版玩家。
            return false;
        }
    }

    private static boolean isFloodgateBedrock(UUID uuid) {
        try {
            Class<?> floodgateApi = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = floodgateApi.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(floodgateApi.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid));
        } catch (Throwable t) {
            return false;
        }
    }
}
