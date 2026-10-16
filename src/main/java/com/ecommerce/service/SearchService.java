package com.ecommerce.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import com.ecommerce.config.AppProperties;
import com.ecommerce.dto.Dtos;
import com.ecommerce.model.Order;
import com.ecommerce.model.OrderItem;
import com.ecommerce.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Elasticsearch: the `orders` index. Search reads ES ONLY.
 *  Documents are denormalized snapshots with deterministic ids `order-<id>`. */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    public static final Map<String, String> SORT_FIELDS = Map.of(
            "order_date", "order_date",
            "total_amount", "total_amount",
            "order_id", "order_id");

    private final ElasticsearchClient es;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public SearchService(ElasticsearchClient es, AppProperties props, ObjectMapper mapper) {
        this.es = es;
        this.props = props;
        this.mapper = mapper;
    }

    public String index() { return props.getEsIndex(); }

    public static String docId(long orderId) { return "order-" + orderId; }

    // ------------------------------------------------------------ mapping ---
    @SuppressWarnings("unchecked")
    private static Map<String, Object> textKeyword() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("fields", Map.of("keyword",
                Map.of("type", "keyword", "ignore_above", 256)));
        return m;
    }

    public static Map<String, Object> ordersMapping() {
        Map<String, Object> propsMap = new LinkedHashMap<>();
        propsMap.put("order_id", Map.of("type", "long"));
        propsMap.put("order_date", Map.of("type", "date"));
        propsMap.put("status", Map.of("type", "keyword"));
        propsMap.put("total_amount", Map.of("type", "double"));
        propsMap.put("updated_at", Map.of("type", "date"));
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("type", "object");
        Map<String, Object> cprops = new LinkedHashMap<>();
        cprops.put("id", Map.of("type", "long"));
        cprops.put("name", textKeyword());
        cprops.put("email", Map.of("type", "keyword"));
        customer.put("properties", cprops);
        propsMap.put("customer", customer);
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "nested");
        Map<String, Object> iprops = new LinkedHashMap<>();
        iprops.put("product_id", Map.of("type", "keyword"));
        iprops.put("title", textKeyword());
        iprops.put("quantity", Map.of("type", "integer"));
        iprops.put("unit_price", Map.of("type", "double"));
        items.put("properties", iprops);
        propsMap.put("items", items);
        return Map.of("mappings", Map.of("properties", propsMap));
    }

    public boolean ensureIndex() {
        return ensureIndex(index());
    }

    public boolean ensureIndex(String index) {
        try {
            boolean exists = es.indices().exists(ExistsRequest.of(b -> b.index(index))).value();
            if (!exists) {
                String body = mapper.writeValueAsString(ordersMapping());
                es.indices().create(c -> c.index(index).withJson(new StringReader(body)));
                log.info("elasticsearch index created: {}", index);
            }
            return true;
        } catch (Exception e) {
            log.warn("elasticsearch ensure_index failed: {}", e.getMessage());
            return false;
        }
    }

    public void deleteIndex(String index) {
        try {
            es.indices().delete(d -> d.index(index).ignoreUnavailable(true));
        } catch (Exception e) {
            log.warn("elasticsearch delete_index failed: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------- documents ---
    /** Build the denormalized ES document from PG rows (snapshots only, never Mongo). */
    public static Map<String, Object> buildOrderDoc(Order order, User user,
                                                    List<OrderItem> items) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("order_id", order.getId());
        doc.put("order_date", isoSecond(order.getOrderDate()));
        doc.put("status", order.getStatus());
        doc.put("total_amount", order.getTotalAmount().doubleValue());
        doc.put("updated_at", isoSecond(order.getUpdatedAt()));
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", user.getId());
        customer.put("name", user.getName());
        customer.put("email", user.getEmail());
        doc.put("customer", customer);
        List<Map<String, Object>> itemDocs = new ArrayList<>();
        for (OrderItem it : items) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("product_id", it.getProductId());
            im.put("title", it.getTitle());
            im.put("quantity", it.getQuantity());
            im.put("unit_price", it.getUnitPrice().doubleValue());
            itemDocs.add(im);
        }
        doc.put("items", itemDocs);
        return doc;
    }

    private static String isoSecond(OffsetDateTime dt) {
        if (dt == null) return null;
        return Money.truncateToSecond(dt).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public void upsertOrderDoc(Map<String, Object> doc) throws Exception {
        upsertOrderDoc(index(), doc);
    }

    public void upsertOrderDoc(String index, Map<String, Object> doc) throws Exception {
        es.index(i -> i.index(index).id(docId(((Number) doc.get("order_id")).longValue()))
                .document(JsonData.of(doc)));
    }

    /** Returns the stored document, null when missing. Throws when ES is down. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getOrderDoc(long orderId) throws Exception {
        return getOrderDoc(index(), orderId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getOrderDoc(String index, long orderId) throws Exception {
        try {
            var resp = es.get(g -> g.index(index).id(docId(orderId)), JsonData.class);
            if (!resp.found()) return null;
            return resp.source().to(Map.class);
        } catch (ElasticsearchException e) {
            if (e.status() == 404) return null;
            throw e;
        }
    }

    public Long countDocs() {
        try {
            var resp = es.count(c -> c.index(index()));
            return resp.count();
        } catch (Exception e) {
            log.warn("elasticsearch count failed: {}", e.getMessage());
            return null;
        }
    }

    public boolean ping() {
        try {
            return es.ping().value();
        } catch (Exception e) {
            return false;
        }
    }

    // --------------------------------------------------------------- query ---
    /** Build the ES query body: bool + multi_match (+ nested) + filters + aggs.
     *  Aggregations run in the SAME filter context as the hits. Pure function. */
    public static Map<String, Object> buildSearchBody(Dtos.SearchRequest p) {
        List<Map<String, Object>> must = new ArrayList<>();
        if (p.q() != null && !p.q().isBlank()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("query", p.q());
            mm.put("fields", List.of("customer.name", "customer.email", "items.title"));
            mm.put("type", "best_fields");
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("path", "items");
            nested.put("query", Map.of("match", Map.of("items.title", p.q())));
            Map<String, Object> bool = new LinkedHashMap<>();
            bool.put("should", List.of(Map.of("multi_match", mm), Map.of("nested", nested)));
            bool.put("minimum_should_match", 1);
            must.add(Map.of("bool", bool));
        }

        List<Map<String, Object>> filters = new ArrayList<>();
        if (p.statuses() != null && !p.statuses().isEmpty()) {
            filters.add(Map.of("terms", Map.of("status", p.statuses())));
        }
        if (p.dateFrom() != null || p.dateTo() != null) {
            Map<String, Object> rng = new LinkedHashMap<>();
            if (p.dateFrom() != null) rng.put("gte", p.dateFrom().toString());
            if (p.dateTo() != null) rng.put("lte", p.dateTo().toString() + "T23:59:59");
            filters.add(Map.of("range", Map.of("order_date", rng)));
        }
        if (p.minTotal() != null || p.maxTotal() != null) {
            Map<String, Object> rng = new LinkedHashMap<>();
            if (p.minTotal() != null) rng.put("gte", p.minTotal());
            if (p.maxTotal() != null) rng.put("lte", p.maxTotal());
            filters.add(Map.of("range", Map.of("total_amount", rng)));
        }

        Map<String, Object> query;
        if (must.isEmpty() && filters.isEmpty()) {
            query = Map.of("match_all", Map.of());
        } else {
            Map<String, Object> bool = new LinkedHashMap<>();
            if (!must.isEmpty()) bool.put("must", must);
            if (!filters.isEmpty()) bool.put("filter", filters);
            query = Map.of("bool", bool);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("sort", List.of(Map.of(
                SORT_FIELDS.getOrDefault(p.sortBy(), "order_date"),
                Map.of("order", p.sortDir()))));
        body.put("from", (p.page() - 1) * p.pageSize());
        body.put("size", p.pageSize());
        Map<String, Object> aggs = new LinkedHashMap<>();
        aggs.put("revenue", Map.of("sum", Map.of("field", "total_amount")));
        aggs.put("by_status", Map.of("terms", Map.of("field", "status", "size", 10)));
        aggs.put("total_orders", Map.of("value_count", Map.of("field", "order_id")));
        body.put("aggs", aggs);
        body.put("track_total_hits", true);
        return body;
    }

    // -------------------------------------------------------------- search ---
    @SuppressWarnings("unchecked")
    public Dtos.SearchResponse search(Dtos.SearchRequest params) throws Exception {
        String bodyJson = mapper.writeValueAsString(buildSearchBody(params));
        SearchResponse<JsonData> resp;
        try {
            resp = es.search(s -> s.index(index()).withJson(new StringReader(bodyJson)),
                    JsonData.class);
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return new Dtos.SearchResponse(0, params.page(), params.pageSize(), 1,
                        List.of(), new Dtos.SearchAggs(0.0, Map.of(), 0));
            }
            throw e;
        }
        long total = resp.hits().total() == null ? 0 : resp.hits().total().value();
        List<Dtos.SearchOrderRow> rows = new ArrayList<>();
        for (var hit : resp.hits().hits()) {
            Map<String, Object> src = hit.source().to(Map.class);
            rows.add(parseHit(src));
        }
        return toResponse(params, total, rows, aggsToMap(resp.aggregations()));
    }

    /** Convert typed ES aggregates to the plain-Map shape toResponse expects. */
    private static Map<String, Object> aggsToMap(
            Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregate> aggs) {
        Map<String, Object> out = new LinkedHashMap<>();
        var rev = aggs.get("revenue");
        Double revVal = (rev != null && rev.isSum()) ? rev.sum().value() : null;
        out.put("revenue", revVal == null ? Map.of() : Map.of("value", revVal));
        var bs = aggs.get("by_status");
        List<Map<String, Object>> buckets = new ArrayList<>();
        if (bs != null && bs.isSterms()) {
            for (var b : bs.sterms().buckets().array()) {
                buckets.add(Map.of("key", b.key().stringValue(), "doc_count", b.docCount()));
            }
        }
        out.put("by_status", Map.of("buckets", buckets));
        var to = aggs.get("total_orders");
        Double toVal = (to != null && to.isValueCount()) ? to.valueCount().value() : null;
        out.put("total_orders", toVal == null ? Map.of() : Map.of("value", toVal));
        return out;
    }

    @SuppressWarnings("unchecked")
    static Dtos.SearchOrderRow parseHit(Map<String, Object> src) {
        Map<String, Object> c = (Map<String, Object>) src.getOrDefault("customer", Map.of());
        return new Dtos.SearchOrderRow(
                ((Number) src.get("order_id")).longValue(),
                coerceDt(src.get("order_date")),
                new Dtos.CustomerDto(
                        ((Number) c.getOrDefault("id", 0)).longValue(),
                        String.valueOf(c.getOrDefault("name", "")),
                        String.valueOf(c.getOrDefault("email", ""))),
                ((Number) src.getOrDefault("total_amount", 0.0)).doubleValue(),
                String.valueOf(src.getOrDefault("status", "PENDING")));
    }

    /** Parse a raw ES search response body (Map) into the API response. Pure function. */
    @SuppressWarnings("unchecked")
    public static Dtos.SearchResponse parseSearchResponse(Map<String, Object> body,
                                                          Dtos.SearchRequest params) {
        Map<String, Object> hits = (Map<String, Object>) body.getOrDefault("hits", Map.of());
        Object totalInfo = hits.getOrDefault("total", 0);
        long total = totalInfo instanceof Map
                ? ((Number) ((Map<String, Object>) totalInfo).getOrDefault("value", 0)).longValue()
                : ((Number) totalInfo).longValue();
        List<Dtos.SearchOrderRow> rows = new ArrayList<>();
        Object hitList = hits.get("hits");
        if (hitList instanceof List) {
            for (Object h : (List<Object>) hitList) {
                Map<String, Object> hm = (Map<String, Object>) h;
                rows.add(parseHit((Map<String, Object>) hm.getOrDefault("_source", Map.of())));
            }
        }
        Map<String, Object> aggs = (Map<String, Object>) body.getOrDefault("aggregations", Map.of());
        return toResponse(params, total, rows, aggs);
    }

    @SuppressWarnings("unchecked")
    private static Dtos.SearchResponse toResponse(Dtos.SearchRequest params, long total,
                                                  List<Dtos.SearchOrderRow> rows,
                                                  Map<String, Object> aggs) {
        Map<String, Object> revenueAgg = (Map<String, Object>) aggs.getOrDefault("revenue", Map.of());
        Object revVal = revenueAgg.get("value");
        double revenue = revVal instanceof Number ? ((Number) revVal).doubleValue() : 0.0;
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Object> byStatusAgg = (Map<String, Object>) aggs.getOrDefault("by_status", Map.of());
        Object buckets = byStatusAgg.get("buckets");
        if (buckets instanceof List) {
            for (Object b : (List<Object>) buckets) {
                Map<String, Object> bm = (Map<String, Object>) b;
                byStatus.put(String.valueOf(bm.get("key")),
                        ((Number) bm.getOrDefault("doc_count", 0)).intValue());
            }
        }
        Map<String, Object> totalAgg = (Map<String, Object>) aggs.getOrDefault("total_orders", Map.of());
        int totalOrders = totalAgg.get("value") instanceof Number
                ? ((Number) totalAgg.get("value")).intValue() : 0;
        int totalPages = total == 0 ? 1 : (int) Math.max(1, (total + params.pageSize() - 1) / params.pageSize());
        return new Dtos.SearchResponse(total, params.page(), params.pageSize(), totalPages,
                rows, new Dtos.SearchAggs(revenue, byStatus, totalOrders));
    }

    static OffsetDateTime coerceDt(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        String s = String.valueOf(value).replace("Z", "+00:00");
        OffsetDateTime dt = OffsetDateTime.parse(s);
        return dt.getOffset() == null ? dt.withOffsetSameInstant(java.time.ZoneOffset.UTC) : dt;
    }
}
