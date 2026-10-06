package com.weatherwidget;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 天气纯逻辑与解析：不依赖 JavaFX/AWT/网络，便于单元测试 */
public final class WeatherLogic {

    private WeatherLogic() {}

    /** 单小时数据点（供设置窗口"预报"分页的逐日图表使用） */
    public record HourlyPoint(int hour, double temp, int precipProb) {}

    /** 单日预报（供设置窗口"预报"分页展示，含该天逐小时序列） */
    public record DailyForecast(String label, String emoji, double tempMax, double tempMin,
                                int precipProb, List<HourlyPoint> hourly) {}

    /** 内部数据类：突变检测用的单点（含绝对时间） */
    public record AlertPoint(LocalDateTime time, double temp, int precipProb, int weatherCode) {}

    /** 内部数据类：预警结果（等级 + 文案） */
    public record WeatherAlert(String level, String text) {}

    /** WMO 天气码 -> emoji */
    public static String weatherEmoji(int code, boolean isDay) {
        if (code == 0) {
            return isDay ? "☀" : "🌙";
        }
        if (code == 1) {
            return isDay ? "🌤" : "🌙";
        }
        if (code == 2) {
            return "⛅";
        }
        if (code == 3) {
            return "☁";
        }
        if (code == 45 || code == 48) {
            return "🌫";
        }
        if (code >= 51 && code <= 57) {
            return "🌦";
        }
        if (code >= 61 && code <= 67) {
            return "🌧";
        }
        if (code >= 71 && code <= 77) {
            return "❄";
        }
        if (code >= 80 && code <= 82) {
            return "🌧";
        }
        if (code >= 85 && code <= 86) {
            return "🌨";
        }
        if (code >= 95) {
            return "⛈";
        }
        return "🌡";
    }

    /** 判断名称是否为"纯坐标串"（坐标为来源的定位结果） */
    public static boolean isCoordinateName(String name) {
        return name != null
                && name.matches("[-+]?\\d+(?:\\.\\d+)?\\s*,\\s*[-+]?\\d+(?:\\.\\d+)?");
    }

    /** 顶部位置标签文本：关闭坐标显示时，去掉名称中的坐标部分 */
    public static String formatLocationLabel(String name, double lat, double lon, boolean showCoordinates) {
        String text = name == null ? "" : name;
        if (!showCoordinates) {
            // 去掉末尾的 "  [纬度, 经度]"
            text = text.replaceAll("\\s*\\[\\s*[-+]?\\d+(?:\\.\\d+)?\\s*,\\s*[-+]?\\d+(?:\\.\\d+)?\\s*\\]$", "").trim();
            // 坐标为来源且未能转换为行政区时，直接显示坐标，避免只显示"已定位"
            if (text.isEmpty() || isCoordinateName(text)) {
                text = String.format("%.4f, %.4f", lat, lon);
            }
        }
        return "📍 " + text;
    }

    /** 日期标签：今天 / 明天 / M/d 周X */
    public static String dayLabel(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "今天";
        }
        if (date.equals(today.plusDays(1))) {
            return "明天";
        }
        String[] week = {"一", "二", "三", "四", "五", "六", "日"};
        return String.format("%d/%d 周%s", date.getMonthValue(), date.getDayOfMonth(),
                week[date.getDayOfWeek().getValue() - 1]);
    }

    /**
     * 当前小时索引：取第一个不早于 now（按小时截断）的时间点下标；
     * 若所有时间点都早于 now，则返回 0（与原始实现一致）。
     */
    public static int currentHourIndex(List<LocalDateTime> times, LocalDateTime now) {
        LocalDateTime nowLocal = now.truncatedTo(ChronoUnit.HOURS);
        for (int i = 0; i < times.size(); i++) {
            if (!times.get(i).isBefore(nowLocal)) {
                return i;
            }
        }
        return 0;
    }

    /** 解析逐日预报 JSON，并按日期分组逐小时数据，产出每日预报列表 */
    public static List<DailyForecast> parseDaily(JsonNode root, LocalDate today) {
        JsonNode daily = root.get("daily");
        JsonNode dates = daily.get("time");
        JsonNode codes = daily.get("weather_code");
        JsonNode maxes = daily.get("temperature_2m_max");
        JsonNode mins = daily.get("temperature_2m_min");
        JsonNode probs = daily.get("precipitation_probability_max");

        // 逐小时数据按日期分组，供点击某天时绘制折线/柱状图
        JsonNode hourly = root.get("hourly");
        JsonNode hTimes = hourly.get("time");
        JsonNode hTemps = hourly.get("temperature_2m");
        JsonNode hProbs = hourly.get("precipitation_probability");
        Map<String, List<HourlyPoint>> byDate = new LinkedHashMap<>();
        for (int i = 0; i < hTimes.size(); i++) {
            LocalDateTime t = LocalDateTime.parse(hTimes.get(i).asText());
            double temp = hTemps.get(i).isNull() ? 0 : hTemps.get(i).asDouble();
            int prob = hProbs.get(i).isNull() ? 0 : hProbs.get(i).asInt();
            byDate.computeIfAbsent(t.toLocalDate().toString(), k -> new ArrayList<>())
                    .add(new HourlyPoint(t.getHour(), temp, prob));
        }

        List<DailyForecast> result = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = LocalDate.parse(dates.get(i).asText());
            result.add(new DailyForecast(
                    dayLabel(date, today),
                    weatherEmoji(codes.get(i).asInt(), true),
                    maxes.get(i).asDouble(),
                    mins.get(i).asDouble(),
                    probs.get(i).asInt(),
                    byDate.getOrDefault(date.toString(), List.of())
            ));
        }
        return result;
    }

    /** 按阈值返回预警等级（取达到的最高档），未达最低档返回 null */
    public static String warningLevel(double value, double blue, double yellow,
                                      double orange, double red) {
        if (value >= red) {
            return "红色";
        }
        if (value >= orange) {
            return "橙色";
        }
        if (value >= yellow) {
            return "黄色";
        }
        if (value >= blue) {
            return "蓝色";
        }
        return null;
    }

    /** 预警等级排序权重（红色最高） */
    public static int levelRank(String level) {
        switch (level) {
            case "红色": return 4;
            case "橙色": return 3;
            case "黄色": return 2;
            case "蓝色": return 1;
            default: return 0;
        }
    }

    /** 提醒文案中的时间标签：当天显示 "HH:00"，跨天显示 "明天 HH:00" */
    public static String alertTimeLabel(LocalDateTime time, LocalDate today) {
        String hm = String.format("%02d:00", time.getHour());
        if (time.toLocalDate().equals(today)) {
            return hm;
        }
        if (time.toLocalDate().equals(today.plusDays(1))) {
            return "明天 " + hm;
        }
        return String.format("%d/%d %s", time.getMonthValue(), time.getDayOfMonth(), hm);
    }

    /**
     * 检测未来窗口内的天气突变（气温骤变 / 降雨增强），取等级最高的一项。
     * 采用蓝/黄/橙/红四级；无突变返回 null。
     */
    public static WeatherAlert detectChange(List<AlertPoint> window, LocalDate today) {
        if (window.size() < 4) {
            return null;
        }
        List<WeatherAlert> candidates = new ArrayList<>();

        // 气温突变：连续 3 小时窗口内的最大变化量
        double maxDelta = 0;
        AlertPoint deltaPoint = null;
        for (int i = 0; i + 3 < window.size(); i++) {
            double d = window.get(i + 3).temp() - window.get(i).temp();
            if (Math.abs(d) > Math.abs(maxDelta)) {
                maxDelta = d;
                deltaPoint = window.get(i);
            }
        }
        String tempLevel = warningLevel(Math.abs(maxDelta), 5, 8, 11, 15);
        if (tempLevel != null && deltaPoint != null) {
            String dir = maxDelta > 0 ? "上升" : "下降";
            candidates.add(new WeatherAlert(tempLevel, String.format("预计 %s 前后气温%s约 %.0f℃",
                    alertTimeLabel(deltaPoint.time(), today), dir, Math.abs(maxDelta))));
        }

        // 降雨突变：最大降水概率 + 天气码强度
        int maxProb = 0;
        int maxCode = 0;
        AlertPoint probPoint = null;
        for (AlertPoint p : window) {
            if (p.precipProb() > maxProb) {
                maxProb = p.precipProb();
                probPoint = p;
            }
            maxCode = Math.max(maxCode, p.weatherCode());
        }
        String rainLevel = null;
        if (maxCode >= 95 && maxProb >= 90) {
            rainLevel = "红色";
        } else if (maxCode >= 80 && maxProb >= 90) {
            rainLevel = "橙色";
        } else if (maxProb >= 90) {
            rainLevel = "橙色";
        } else if (maxProb >= 80) {
            rainLevel = "黄色";
        } else if (maxProb >= 70) {
            rainLevel = "蓝色";
        }
        if (rainLevel != null && probPoint != null) {
            candidates.add(new WeatherAlert(rainLevel, String.format("预计 %s 起降雨概率升至 %d%%",
                    alertTimeLabel(probPoint.time(), today), maxProb)));
        }

        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort((a, b) -> Integer.compare(levelRank(b.level()), levelRank(a.level())));
        return candidates.get(0);
    }
}
