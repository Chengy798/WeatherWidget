package com.weatherwidget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** LocationService 网络层单元测试：mock HttpClient，完全离线 */
class LocationServiceTest {

    @AfterEach
    void tearDown() {
        // 恢复默认 HttpClient，避免静态状态串味
        LocationService.resetHttpClientForTest();
    }

    // ===== 工具方法 =====

    private static HttpResponse<String> response(String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(body);
        return resp;
    }

    /** 允许抛出受检异常的请求路由器 */
    @FunctionalInterface
    private interface Router {
        HttpResponse<String> route(HttpRequest request) throws Exception;
    }

    /** 按请求分流的 mock HttpClient；路由实现可抛异常表示连接失败 */
    private static HttpClient clientRouting(Router router) throws Exception {
        HttpClient client = mock(HttpClient.class);
        doAnswer(inv -> router.route(inv.getArgument(0)))
                .when(client).send(any(HttpRequest.class), any());
        return client;
    }

    // ===== locateByIp =====

    @Test
    void locateByIp主源成功() throws Exception {
        LocationService.setHttpClientForTest(clientRouting(req -> {
            if (req.uri().getHost().equals("ipwho.is")) {
                return response("{\"success\":true,\"latitude\":39.9,\"longitude\":116.4,"
                        + "\"city\":\"北京\",\"country\":\"中国\"}");
            }
            throw new IOException("unexpected host: " + req.uri().getHost());
        }));

        LocationService.Location loc = LocationService.locateByIp();
        assertEquals(39.9, loc.latitude());
        assertEquals(116.4, loc.longitude());
        assertEquals("北京, 中国", loc.name());
    }

    @Test
    void locateByIp主源失败回退备源() throws Exception {
        LocationService.setHttpClientForTest(clientRouting(req -> {
            String host = req.uri().getHost();
            if (host.equals("ipwho.is")) {
                throw new IOException("primary down");
            }
            if (host.equals("ip-api.com")) {
                return response("{\"status\":\"success\",\"lat\":31.2,\"lon\":121.5,"
                        + "\"city\":\"上海\",\"country\":\"中国\"}");
            }
            throw new IOException("unexpected host: " + host);
        }));

        LocationService.Location loc = LocationService.locateByIp();
        assertEquals(31.2, loc.latitude());
        assertEquals(121.5, loc.longitude());
        assertEquals("上海, 中国", loc.name());
    }

    @Test
    void locateByIp双源都失败抛异常() throws Exception {
        LocationService.setHttpClientForTest(clientRouting(req -> {
            throw new IOException("all down: " + req.uri().getHost());
        }));

        IllegalStateException ex = assertThrows(IllegalStateException.class, LocationService::locateByIp);
        assertTrue(ex.getMessage().contains("自动定位失败"), ex.getMessage());
    }

    @Test
    void locateByIp返回零坐标时抛异常() throws Exception {
        // 主备源都返回 (0,0)，被 buildLocation 判为无效坐标，最终整体抛出
        LocationService.setHttpClientForTest(clientRouting(req -> {
            String host = req.uri().getHost();
            if (host.equals("ipwho.is")) {
                return response("{\"success\":true,\"latitude\":0,\"longitude\":0,"
                        + "\"city\":\"\",\"country\":\"\"}");
            }
            if (host.equals("ip-api.com")) {
                return response("{\"status\":\"success\",\"lat\":0,\"lon\":0,"
                        + "\"city\":\"\",\"country\":\"\"}");
            }
            throw new IOException("unexpected host: " + host);
        }));

        IllegalStateException ex = assertThrows(IllegalStateException.class, LocationService::locateByIp);
        // 无效坐标在双源容错后会被包装为"自动定位失败"
        assertTrue(ex.getMessage().contains("无效坐标") || ex.getMessage().contains("自动定位失败"),
                ex.getMessage());
    }

    // ===== searchCity =====

    /** name=beijing 与 name=beijing市 两次查询返回含重复项的结果 */
    private static HttpClient geocodingClient() throws Exception {
        String dup = "{\"latitude\":39.9,\"longitude\":116.4,\"name\":\"北京\","
                + "\"admin1\":\"北京市\",\"country\":\"中国\",\"population\":21540000}";
        return clientRouting(req -> {
            String query = req.uri().getRawQuery();
            if (query != null && query.contains("%E5%B8%82")) {
                // 追加"市"后的第二次查询：一条重复 + 一条新结果
                return response("{\"results\":[" + dup + ","
                        + "{\"latitude\":30.0,\"longitude\":120.0,\"name\":\"北京镇\","
                        + "\"admin1\":\"浙江省\",\"country\":\"中国\",\"population\":5000}]}");
            }
            // 第一次查询：一条结果 + 其完全重复副本 + 一条人口较小的结果
            return response("{\"results\":[" + dup + "," + dup + ","
                    + "{\"latitude\":41.6,\"longitude\":121.8,\"name\":\"北镇\","
                    + "\"admin1\":\"辽宁省\",\"country\":\"中国\",\"population\":100000}]}");
        });
    }

    @Test
    void searchCity去重并按人口降序() throws Exception {
        LocationService.setHttpClientForTest(geocodingClient());

        List<LocationService.Location> results = LocationService.searchCity("beijing", true);

        // 两条完全重复的条目只保留一条，跨两次查询的重复项也去重
        assertEquals(3, results.size());
        // 按 population 降序：21540000 -> 100000 -> 5000
        assertEquals("北京 (北京市 · 中国)  [39.90, 116.40]", results.get(0).name());
        assertEquals("北镇 (辽宁省 · 中国)  [41.60, 121.80]", results.get(1).name());
        assertEquals("北京镇 (浙江省 · 中国)  [30.00, 120.00]", results.get(2).name());
    }

    @Test
    void searchCity坐标开关影响标签() throws Exception {
        LocationService.setHttpClientForTest(geocodingClient());

        List<LocationService.Location> withCoords = LocationService.searchCity("beijing", true);
        assertTrue(withCoords.get(0).name().contains("[39.90, 116.40]"));

        List<LocationService.Location> withoutCoords = LocationService.searchCity("beijing", false);
        assertFalse(withoutCoords.get(0).name().contains("["));
        assertEquals("北京 (北京市 · 中国)", withoutCoords.get(0).name());
    }

    // ===== reverseGeocodeAdmin =====

    @Test
    void reverseGeocodeAdmin成功() throws Exception {
        LocationService.setHttpClientForTest(clientRouting(req -> {
            if (req.uri().getHost().equals("api.bigdatacloud.net")) {
                return response("{\"city\":\"大连市\",\"locality\":\"西岗区\","
                        + "\"principalSubdivision\":\"辽宁省\",\"countryName\":\"中国\"}");
            }
            throw new IOException("unexpected host: " + req.uri().getHost());
        }));

        assertEquals("大连市 · 辽宁省", LocationService.reverseGeocodeAdmin(38.9, 121.6));
    }

    @Test
    void reverseGeocodeAdmin主行政区字段全空返回null() throws Exception {
        LocationService.setHttpClientForTest(clientRouting(req ->
                response("{\"city\":\"\",\"locality\":\"\","
                        + "\"principalSubdivision\":\"\",\"countryName\":\"\"}")));

        assertEquals(null, LocationService.reverseGeocodeAdmin(38.9, 121.6));
    }

    @Test
    void reverseGeocodeAdmin请求异常返回null() throws Exception {
        LocationService.setHttpClientForTest(clientRouting(req -> {
            throw new IOException("boom");
        }));

        assertEquals(null, LocationService.reverseGeocodeAdmin(38.9, 121.6));
    }
}
