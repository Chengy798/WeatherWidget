package com.weatherwidget;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Windows 开机自启动。
 * <p>
 * 通过写入注册表 {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run} 实现，
 * 仅对当前用户生效、无需管理员权限。仅在应用已被 jpackage 打包为 exe 时可用
 * （此时系统属性 {@code jpackage.app-path} 指向启动器 exe）。
 */
public final class AutoStart {

    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String APP_NAME = "WeatherWidget";

    private AutoStart() {
    }

    /** 当前运行方式是否支持开机自启动（Windows 且已打包为 exe） */
    public static boolean isSupported() {
        return isWindows() && getExecutablePath() != null;
    }

    /** 是否已设置开机自启动 */
    public static boolean isEnabled() {
        if (!isSupported()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("reg", "query", RUN_KEY, "/v", APP_NAME)
                    .redirectErrorStream(true)
                    .start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** 设置或取消开机自启动 */
    public static void setEnabled(boolean enabled) throws IOException, InterruptedException {
        if (!isSupported()) {
            throw new IllegalStateException("仅打包为 exe 后可用");
        }
        ProcessBuilder pb;
        if (enabled) {
            String exe = getExecutablePath();
            pb = new ProcessBuilder("reg", "add", RUN_KEY,
                    "/v", APP_NAME, "/t", "REG_SZ", "/d", "\"" + exe + "\"", "/f");
        } else {
            pb = new ProcessBuilder("reg", "delete", RUN_KEY, "/v", APP_NAME, "/f");
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor(5, TimeUnit.SECONDS);
        p.destroyForcibly();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** jpackage 打包后由启动器注入，指向 <app>\WeatherWidget.exe */
    private static String getExecutablePath() {
        String path = System.getProperty("jpackage.app-path");
        return (path == null || path.isBlank()) ? null : path;
    }
}
