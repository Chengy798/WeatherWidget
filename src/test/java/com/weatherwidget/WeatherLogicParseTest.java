package com.weatherwidget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WeatherLogic.parseDaily 解析层单元测试：读取本地 fixture，不发起网络请求 */
class WeatherLogicParseTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private static JsonNode loadFixture() throws Exception {
        try (InputStream in = WeatherLogicParseTest.class.getResourceAsStream("/daily.json")) {
            assertNotNull(in, "未找到测试资源 daily.json");
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void parseDaily天数与日期标签正确() throws Exception {
        JsonNode root = loadFixture();
        List<WeatherLogic.DailyForecast> days = WeatherLogic.parseDaily(root, TODAY);

        assertEquals(3, days.size());
        assertEquals("今天", days.get(0).label());
        assertEquals("明天", days.get(1).label());
        assertEquals("10/8 周四", days.get(2).label());
    }

    @Test
    void parseDaily每日字段与emoji非空() throws Exception {
        JsonNode root = loadFixture();
        List<WeatherLogic.DailyForecast> days = WeatherLogic.parseDaily(root, TODAY);

        WeatherLogic.DailyForecast d0 = days.get(0);
        assertEquals(24.5, d0.tempMax());
        assertEquals(14.0, d0.tempMin());
        assertEquals(10, d0.precipProb());
        assertEquals(WeatherLogic.weatherEmoji(0, true), d0.emoji());
        assertFalse(d0.emoji().isEmpty());

        // 每一天都应有 24 条逐小时数据
        for (WeatherLogic.DailyForecast d : days) {
            assertEquals(24, d.hourly().size());
        }
    }

    @Test
    void parseDaily逐小时按日期分组且小时值正确() throws Exception {
        JsonNode root = loadFixture();
        List<WeatherLogic.DailyForecast> days = WeatherLogic.parseDaily(root, TODAY);

        WeatherLogic.HourlyPoint d0h0 = days.get(0).hourly().get(0);
        assertEquals(0, d0h0.hour());
        assertEquals(14.0, d0h0.temp());
        assertEquals(0, d0h0.precipProb());

        assertEquals(3, days.get(0).hourly().get(3).hour());
        assertEquals(23, days.get(2).hourly().get(23).hour());
        assertEquals(13.0, days.get(2).hourly().get(0).temp());
        assertEquals(80, days.get(2).hourly().get(0).precipProb());
    }

    @Test
    void parseDaily缺失值按0处理() throws Exception {
        JsonNode root = loadFixture();
        List<WeatherLogic.DailyForecast> days = WeatherLogic.parseDaily(root, TODAY);

        // 2026-10-06T03:00 的温度为 null -> 0
        assertEquals(0.0, days.get(0).hourly().get(3).temp());
        // 2026-10-07T05:00 的降水概率为 null -> 0
        assertEquals(0, days.get(1).hourly().get(5).precipProb());
    }

    @Test
    void parseDaily小时数与众数一致() throws Exception {
        JsonNode root = loadFixture();
        List<WeatherLogic.DailyForecast> days = WeatherLogic.parseDaily(root, TODAY);

        int total = 0;
        for (WeatherLogic.DailyForecast d : days) {
            total += d.hourly().size();
        }
        assertTrue(total == root.get("hourly").get("time").size());
    }
}
