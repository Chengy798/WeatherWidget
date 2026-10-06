package com.weatherwidget;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WeatherLogic 纯规则单元测试（不依赖 JavaFX / AWT / 网络） */
class WeatherLogicTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    // ===== weatherEmoji =====

    @Test
    void weatherEmoji按天气码与昼夜映射() {
        assertEquals("☀", WeatherLogic.weatherEmoji(0, true));
        assertEquals("🌙", WeatherLogic.weatherEmoji(0, false));
        assertEquals("🌤", WeatherLogic.weatherEmoji(1, true));
        assertEquals("🌙", WeatherLogic.weatherEmoji(1, false));
        assertEquals("⛅", WeatherLogic.weatherEmoji(2, true));
        assertEquals("☁", WeatherLogic.weatherEmoji(3, true));
        assertEquals("🌫", WeatherLogic.weatherEmoji(45, true));
        assertEquals("🌫", WeatherLogic.weatherEmoji(48, false));
        assertEquals("🌦", WeatherLogic.weatherEmoji(53, true));
        assertEquals("🌧", WeatherLogic.weatherEmoji(63, true));
        assertEquals("❄", WeatherLogic.weatherEmoji(73, true));
        assertEquals("🌧", WeatherLogic.weatherEmoji(81, true));
        assertEquals("🌨", WeatherLogic.weatherEmoji(85, true));
        assertEquals("⛈", WeatherLogic.weatherEmoji(95, true));
        assertEquals("⛈", WeatherLogic.weatherEmoji(99, false));
        assertEquals("🌡", WeatherLogic.weatherEmoji(50, true));
    }

    @Test
    void weatherEmoji不含变体选择符() {
        int[] codes = {0, 1, 2, 3, 45, 48, 53, 63, 73, 81, 85, 95, 99, 50};
        for (int code : codes) {
            assertFalse(WeatherLogic.weatherEmoji(code, true).contains("\uFE0F"),
                    "code=" + code + " 不应包含 U+FE0F");
            assertFalse(WeatherLogic.weatherEmoji(code, false).contains("\uFE0F"),
                    "code=" + code + " 不应包含 U+FE0F");
        }
    }

    // ===== dayLabel =====

    @Test
    void dayLabel今天明天与其余日期() {
        assertEquals("今天", WeatherLogic.dayLabel(TODAY, TODAY));
        assertEquals("明天", WeatherLogic.dayLabel(TODAY.plusDays(1), TODAY));
        assertEquals("10/8 周四", WeatherLogic.dayLabel(LocalDate.of(2026, 10, 8), TODAY));
        assertEquals("12/31 周四", WeatherLogic.dayLabel(LocalDate.of(2026, 12, 31), TODAY));
    }

    // ===== warningLevel =====

    @Test
    void warningLevel阈值边界() {
        assertNull(WeatherLogic.warningLevel(4.9, 5, 8, 11, 15));
        assertEquals("蓝色", WeatherLogic.warningLevel(5, 5, 8, 11, 15));
        assertEquals("黄色", WeatherLogic.warningLevel(8, 5, 8, 11, 15));
        assertEquals("橙色", WeatherLogic.warningLevel(11, 5, 8, 11, 15));
        assertEquals("红色", WeatherLogic.warningLevel(15, 5, 8, 11, 15));
        assertEquals("红色", WeatherLogic.warningLevel(16, 5, 8, 11, 15));
    }

    // ===== levelRank =====

    @Test
    void levelRank红色最高未知最低() {
        assertTrue(WeatherLogic.levelRank("红色") > WeatherLogic.levelRank("橙色"));
        assertTrue(WeatherLogic.levelRank("橙色") > WeatherLogic.levelRank("黄色"));
        assertTrue(WeatherLogic.levelRank("黄色") > WeatherLogic.levelRank("蓝色"));
        assertTrue(WeatherLogic.levelRank("蓝色") > WeatherLogic.levelRank("未知"));
    }

    // ===== alertTimeLabel =====

    @Test
    void alertTimeLabel当天次日与更远日期() {
        assertEquals("14:00", WeatherLogic.alertTimeLabel(LocalDateTime.of(2026, 10, 6, 14, 0), TODAY));
        assertEquals("明天 09:00", WeatherLogic.alertTimeLabel(LocalDateTime.of(2026, 10, 7, 9, 0), TODAY));
        assertEquals("10/9 08:00", WeatherLogic.alertTimeLabel(LocalDateTime.of(2026, 10, 9, 8, 0), TODAY));
    }

    // ===== detectChange =====

    @Test
    void detectChange气温骤降达黄色() {
        List<WeatherLogic.AlertPoint> window = new ArrayList<>();
        window.add(point(TODAY, 12, 20, 0, 0));
        window.add(point(TODAY, 13, 17, 0, 0));
        window.add(point(TODAY, 14, 14, 0, 0));
        window.add(point(TODAY, 15, 11, 0, 0));

        WeatherLogic.WeatherAlert alert = WeatherLogic.detectChange(window, TODAY);
        assertEquals("黄色", alert.level());
        assertTrue(alert.text().contains("气温下降"), alert.text());
        assertTrue(alert.text().contains("9"), alert.text());
        assertTrue(alert.text().contains("12:00"), alert.text());
    }

    @Test
    void detectChange气温回升文案含上升() {
        List<WeatherLogic.AlertPoint> window = new ArrayList<>();
        window.add(point(TODAY, 12, 11, 0, 0));
        window.add(point(TODAY, 13, 14, 0, 0));
        window.add(point(TODAY, 14, 17, 0, 0));
        window.add(point(TODAY, 15, 20, 0, 0));

        WeatherLogic.WeatherAlert alert = WeatherLogic.detectChange(window, TODAY);
        assertEquals("黄色", alert.level());
        assertTrue(alert.text().contains("上升"), alert.text());
    }

    @Test
    void detectChange降雨概率90为橙色() {
        List<WeatherLogic.AlertPoint> window = new ArrayList<>();
        window.add(point(TODAY, 12, 20, 10, 61));
        window.add(point(TODAY, 13, 20, 30, 61));
        window.add(point(TODAY, 14, 20, 90, 61));
        window.add(point(TODAY, 15, 20, 80, 61));

        WeatherLogic.WeatherAlert alert = WeatherLogic.detectChange(window, TODAY);
        assertEquals("橙色", alert.level());
        assertTrue(alert.text().contains("降雨概率"), alert.text());
        assertTrue(alert.text().contains("90%"), alert.text());
    }

    @Test
    void detectChange无突变返回null() {
        List<WeatherLogic.AlertPoint> window = new ArrayList<>();
        window.add(point(TODAY, 12, 20, 10, 0));
        window.add(point(TODAY, 13, 20, 10, 0));
        window.add(point(TODAY, 14, 21, 10, 0));
        window.add(point(TODAY, 15, 20, 10, 0));

        assertNull(WeatherLogic.detectChange(window, TODAY));
    }

    @Test
    void detectChange窗口点不足返回null() {
        List<WeatherLogic.AlertPoint> window = new ArrayList<>();
        window.add(point(TODAY, 12, 20, 90, 61));
        window.add(point(TODAY, 13, 20, 90, 61));
        window.add(point(TODAY, 14, 20, 90, 61));

        assertNull(WeatherLogic.detectChange(window, TODAY));
    }

    @Test
    void detectChange同时有气温与降雨时返回等级更高者() {
        List<WeatherLogic.AlertPoint> window = new ArrayList<>();
        // 气温骤降 9℃ -> 黄色；降雨概率 90% -> 橙色（更高）
        window.add(point(TODAY, 12, 20, 10, 61));
        window.add(point(TODAY, 13, 17, 30, 61));
        window.add(point(TODAY, 14, 14, 90, 61));
        window.add(point(TODAY, 15, 11, 85, 61));

        WeatherLogic.WeatherAlert alert = WeatherLogic.detectChange(window, TODAY);
        assertEquals("橙色", alert.level());
        assertTrue(alert.text().contains("降雨概率"), alert.text());
    }

    // ===== currentHourIndex =====

    @Test
    void currentHourIndex取首个不早于now的时间点() {
        List<LocalDateTime> times = List.of(
                LocalDateTime.of(2026, 10, 6, 8, 0),
                LocalDateTime.of(2026, 10, 6, 9, 0),
                LocalDateTime.of(2026, 10, 6, 10, 0),
                LocalDateTime.of(2026, 10, 6, 11, 0));

        assertEquals(1, WeatherLogic.currentHourIndex(times, LocalDateTime.of(2026, 10, 6, 9, 0)));
        assertEquals(0, WeatherLogic.currentHourIndex(times, LocalDateTime.of(2026, 10, 6, 7, 0)));
    }

    @Test
    void currentHourIndex按小时截断now() {
        List<LocalDateTime> times = List.of(
                LocalDateTime.of(2026, 10, 6, 8, 0),
                LocalDateTime.of(2026, 10, 6, 9, 0),
                LocalDateTime.of(2026, 10, 6, 10, 0));

        // 09:30 截断到 09:00 -> 命中 09:00
        assertEquals(1, WeatherLogic.currentHourIndex(times, LocalDateTime.of(2026, 10, 6, 9, 30)));
    }

    @Test
    void currentHourIndex跨日场景() {
        List<LocalDateTime> times = List.of(
                LocalDateTime.of(2026, 10, 6, 23, 0),
                LocalDateTime.of(2026, 10, 7, 0, 0),
                LocalDateTime.of(2026, 10, 7, 1, 0));

        assertEquals(0, WeatherLogic.currentHourIndex(times, LocalDateTime.of(2026, 10, 6, 23, 30)));
        assertEquals(1, WeatherLogic.currentHourIndex(times, LocalDateTime.of(2026, 10, 7, 0, 10)));
    }

    @Test
    void currentHourIndex全部早于now时返回0() {
        List<LocalDateTime> times = List.of(
                LocalDateTime.of(2026, 10, 6, 8, 0),
                LocalDateTime.of(2026, 10, 6, 9, 0));

        assertEquals(0, WeatherLogic.currentHourIndex(times, LocalDateTime.of(2026, 10, 6, 20, 0)));
    }

    // ===== formatLocationLabel / isCoordinateName =====

    @Test
    void formatLocationLabel显示坐标时原样返回() {
        assertEquals("📍 北京  [39.91, 116.40]",
                WeatherLogic.formatLocationLabel("北京  [39.91, 116.40]", 39.9075, 116.3972, true));
    }

    @Test
    void formatLocationLabel不显示坐标时去掉后缀坐标() {
        assertEquals("📍 北京",
                WeatherLogic.formatLocationLabel("北京  [39.91, 116.40]", 39.9075, 116.3972, false));
    }

    @Test
    void formatLocationLabel名称本身为坐标串时显示坐标() {
        assertEquals("📍 " + String.format("%.4f, %.4f", 39.9075, 116.3972),
                WeatherLogic.formatLocationLabel("39.9075, 116.3972", 39.9075, 116.3972, false));
    }

    @Test
    void formatLocationLabel名称为空时显示坐标() {
        assertEquals("📍 " + String.format("%.4f, %.4f", 39.9075, 116.3972),
                WeatherLogic.formatLocationLabel("", 39.9075, 116.3972, false));
        assertEquals("📍 " + String.format("%.4f, %.4f", 39.9075, 116.3972),
                WeatherLogic.formatLocationLabel(null, 39.9075, 116.3972, false));
    }

    @Test
    void isCoordinateName识别纯坐标串() {
        assertTrue(WeatherLogic.isCoordinateName("39.9075, 116.3972"));
        assertTrue(WeatherLogic.isCoordinateName("-33.86, 151.21"));
        assertFalse(WeatherLogic.isCoordinateName("北京"));
        assertFalse(WeatherLogic.isCoordinateName(null));
    }

    private static WeatherLogic.AlertPoint point(LocalDate date, int hour, double temp,
                                                 int precipProb, int weatherCode) {
        return new WeatherLogic.AlertPoint(
                LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)), temp, precipProb, weatherCode);
    }
}
