package com.ecommerce.service;

import com.ecommerce.dto.Dtos;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the ES query builder (no database needed). */
class SearchQueryBuilderTest {

    private Dtos.SearchRequest req(String q, List<String> statuses, LocalDate from,
                                   LocalDate to, Double min, Double max,
                                   String sortBy, String sortDir, int page, int pageSize) {
        return new Dtos.SearchRequest(q, statuses, from, to, min, max, sortBy, sortDir, page, pageSize);
    }

    @Test
    void emptyParamsBuildMatchAll() {
        Map<String, Object> body = SearchService.buildSearchBody(req(null, null, null, null, null, null, "order_date", "desc", 1, 20));
        assertEquals(Map.of("match_all", Map.of()), body.get("query"));
        assertEquals(0, body.get("from"));
        assertEquals(20, body.get("size"));
        assertTrue(body.containsKey("aggs"));
        assertEquals(true, body.get("track_total_hits"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void qBuildsMultiMatchPlusNested() {
        Map<String, Object> body = SearchService.buildSearchBody(req("wireless", null, null, null, null, null, "order_date", "desc", 1, 20));
        Map<String, Object> bool = (Map<String, Object>) ((Map<String, Object>) body.get("query")).get("bool");
        List<Map<String, Object>> must = (List<Map<String, Object>>) bool.get("must");
        assertEquals(1, must.size());
        Map<String, Object> inner = (Map<String, Object>) must.get(0).get("bool");
        List<Map<String, Object>> should = (List<Map<String, Object>>) inner.get("should");
        assertEquals(2, should.size());
        assertEquals(1, inner.get("minimum_should_match"));
        Map<String, Object> mm = (Map<String, Object>) should.get(0).get("multi_match");
        assertEquals("wireless", mm.get("query"));
        assertEquals(List.of("customer.name", "customer.email", "items.title"), mm.get("fields"));
        Map<String, Object> nested = (Map<String, Object>) should.get(1).get("nested");
        assertEquals("items", nested.get("path"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filtersBuildTermsAndRanges() {
        Map<String, Object> body = SearchService.buildSearchBody(req(null,
                List.of("PENDING", "SHIPPED"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-10-16"),
                30.0, 150.0, "total_amount", "asc", 2, 10));
        Map<String, Object> bool = (Map<String, Object>) ((Map<String, Object>) body.get("query")).get("bool");
        List<Map<String, Object>> filters = (List<Map<String, Object>>) bool.get("filter");
        assertEquals(3, filters.size());
        // date range inclusive end-of-day
        Map<String, Object> dateRange = (Map<String, Object>) ((Map<String, Object>) filters.get(1).get("range")).get("order_date");
        assertEquals("2026-07-01", dateRange.get("gte"));
        assertEquals("2026-10-16T23:59:59", dateRange.get("lte"));
        // pagination + sort
        assertEquals(10, body.get("from"));
        assertEquals(10, body.get("size"));
        List<Map<String, Object>> sort = (List<Map<String, Object>>) body.get("sort");
        assertEquals(Map.of("order", "asc"), sort.get(0).get("total_amount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggsCoverRevenueByStatusAndTotal() {
        Map<String, Object> body = SearchService.buildSearchBody(req(null, null, null, null, null, null, "order_date", "desc", 1, 20));
        Map<String, Object> aggs = (Map<String, Object>) body.get("aggs");
        assertEquals(Map.of("sum", Map.of("field", "total_amount")), aggs.get("revenue"));
        assertEquals(Map.of("terms", Map.of("field", "status", "size", 10)), aggs.get("by_status"));
        assertEquals(Map.of("value_count", Map.of("field", "order_id")), aggs.get("total_orders"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseSearchResponseMapsHitsAndAggs() {
        Map<String, Object> body = Map.of(
                "hits", Map.of(
                        "total", Map.of("value", 2),
                        "hits", List.of(
                                Map.of("_source", Map.of(
                                        "order_id", 7,
                                        "order_date", "2026-10-01T10:00:00+00:00",
                                        "status", "PENDING",
                                        "total_amount", 99.99,
                                        "customer", Map.of("id", 3, "name", "Wendy Wireless",
                                                "email", "wendy.wireless@example.com"))))),
                "aggregations", Map.of(
                        "revenue", Map.of("value", 199.98),
                        "by_status", Map.of("buckets", List.of(
                                Map.of("key", "PENDING", "doc_count", 2))),
                        "total_orders", Map.of("value", 2)));
        Dtos.SearchRequest p = req(null, null, null, null, null, null, "order_date", "desc", 1, 20);
        Dtos.SearchResponse resp = SearchService.parseSearchResponse(body, p);
        assertEquals(2, resp.total());
        assertEquals(1, resp.totalPages());
        assertEquals(7, resp.orders().get(0).orderId());
        assertEquals("Wendy Wireless", resp.orders().get(0).customer().name());
        assertEquals(199.98, resp.aggs().revenue(), 0.001);
        assertEquals(2, resp.aggs().byStatus().get("PENDING"));
        assertEquals(2, resp.aggs().totalOrders());
    }
}
