package com.ecommerce.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.ecommerce.config.AppProperties;
import com.ecommerce.model.Order;
import com.ecommerce.model.OrderItem;
import com.ecommerce.model.Outbox;
import com.ecommerce.model.SyncState;
import com.ecommerce.model.User;
import com.ecommerce.repository.OrderItemRepository;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.repository.OutboxRepository;
import com.ecommerce.repository.SyncStateRepository;
import com.ecommerce.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Synchronization strategies: dual-write, transactional outbox, polling.
 *  All upserts use the deterministic id `order-<order_id>` (idempotent). */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    public static final String POLLING_WATERMARK_KEY = "polling_watermark";
    private static final int OUTBOX_BATCH = 50;
    private static final int POLLING_BATCH = 500;

    private final ElasticsearchClient es;
    private final AppProperties props;
    private final SearchService search;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final UserRepository users;
    private final OutboxRepository outbox;
    private final SyncStateRepository syncState;

    public SyncService(ElasticsearchClient es, AppProperties props, SearchService search,
                       OrderRepository orders, OrderItemRepository items, UserRepository users,
                       OutboxRepository outbox, SyncStateRepository syncState) {
        this.es = es;
        this.props = props;
        this.search = search;
        this.orders = orders;
        this.items = items;
        this.users = users;
        this.outbox = outbox;
        this.syncState = syncState;
    }

    public String strategy() { return props.getSyncStrategy(); }

    public Long searchCountDocs() { return search.countDocs(); }

    // ------------------------------------------------------------- push ---
    public void pushOrderDoc(Map<String, Object> doc) throws Exception {
        search.upsertOrderDoc(doc);
    }

    // ------------------------------------------------------------ outbox ---
    /** Drain due outbox rows once. Returns {processed, failed}. */
    @Transactional
    public Map<String, Integer> processOutboxOnce() {
        int processed = 0, failed = 0;
        outbox.flush(); // ensure native query sees pending inserts
        List<Outbox> batch = outbox.claimDueBatch(
                OffsetDateTime.now(ZoneOffset.UTC), OUTBOX_BATCH);
        for (Outbox row : batch) {
            // rows are row-locked (SKIP LOCKED); status flips to done/pending/failed below
            try {
                search.upsertOrderDoc(row.getPayload());
                row.setStatus("done");
                row.setLastError(null);
                processed++;
            } catch (Exception e) {
                log.warn("outbox event {} failed: {}", row.getId(), e.getMessage());
                int attempts = row.getAttempts() + 1;
                row.setAttempts(attempts);
                row.setLastError(truncate(e.getMessage(), 2000));
                if (attempts >= props.getOutboxMaxAttempts()) {
                    row.setStatus("failed");
                } else {
                    row.setStatus("pending");
                    long delay = 1L << attempts; // 2^attempts seconds
                    row.setNextAttemptAt(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delay));
                }
                failed++;
            }
        }
        return Map.of("processed", processed, "failed", failed);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ----------------------------------------------------------- polling ---
    /** One polling pass: upsert orders newer than the watermark, advance it. */
    @Transactional
    public Map<String, Object> runPollingCycle() throws Exception {
        OffsetDateTime watermark = getWatermark();
        OffsetDateTime after = watermark != null ? watermark
                : OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        List<Order> batch = orders.findUpdatedAfter(after, PageRequest.of(0, POLLING_BATCH));
        if (batch.isEmpty()) {
            return Map.of("synced", 0, "watermark", watermark == null ? null : watermark.toString());
        }
        OffsetDateTime max = watermark;
        int synced = 0;
        for (Order o : batch) {
            Map<String, Object> doc = buildDoc(o);
            search.upsertOrderDoc(doc); // throws -> watermark NOT advanced
            synced++;
            if (max == null || o.getUpdatedAt().isAfter(max)) max = o.getUpdatedAt();
        }
        setWatermark(max);
        log.info("polling cycle synced={}", synced);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("synced", synced);
        result.put("watermark", max.toString());
        return result;
    }

    private Map<String, Object> buildDoc(Order o) {
        User u = users.findById(o.getUserId()).orElseThrow();
        List<OrderItem> its = items.findByOrderIdOrderByIdAsc(o.getId());
        return SearchService.buildOrderDoc(o, u, its);
    }

    @Transactional(readOnly = true)
    public OffsetDateTime getWatermark() {
        return syncState.findById(POLLING_WATERMARK_KEY)
                .map(s -> OffsetDateTime.parse(s.getValue())).orElse(null);
    }

    @Transactional
    public void setWatermark(OffsetDateTime value) {
        SyncState s = syncState.findById(POLLING_WATERMARK_KEY).orElseGet(SyncState::new);
        s.setKey(POLLING_WATERMARK_KEY);
        s.setValue(value.toString());
        s.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        syncState.save(s);
    }

    // ----------------------------------------------------------- reindex ---
    /** Full rebuild of the ES index from PostgreSQL (snapshots only). */
    public long reindexAll() throws Exception {
        return reindexAll(search.index());
    }

    public long reindexAll(String index) throws Exception {
        search.ensureIndex(index);
        List<Order> all = orders.findAll(
                org.springframework.data.domain.Sort.by("updatedAt").ascending()
                        .and(org.springframework.data.domain.Sort.by("id").ascending()));
        long count = 0;
        List<Map<String, Object>> batch = new ArrayList<>();
        for (Order o : all) {
            batch.add(buildDoc(o));
            if (batch.size() >= 100) {
                for (Map<String, Object> d : batch) search.upsertOrderDoc(index, d);
                count += batch.size();
                batch.clear();
            }
        }
        for (Map<String, Object> d : batch) search.upsertOrderDoc(index, d);
        count += batch.size();
        try {
            String idx = index;
            es.indices().refresh(r -> r.index(idx));
        } catch (Exception ignored) {}
        log.info("reindex complete indexed={}", count);
        return count;
    }

    // ------------------------------------------------------------ workers ---
    /** dual_write repair loop (also drains dual-write fallback rows). */
    @Scheduled(fixedDelayString = "#{${app.repair-loop-seconds:10} * 1000}")
    public void repairLoop() {
        if (!"dual_write".equals(strategy())) return;
        try {
            Map<String, Integer> r = processOutboxOnce();
            if (r.get("processed") > 0 || r.get("failed") > 0) log.info("repair cycle {}", r);
        } catch (Exception e) {
            log.warn("repair cycle failed: {}", e.getMessage());
        }
    }

    /** outbox worker. */
    @Scheduled(fixedDelayString = "#{${app.outbox-poll-seconds:2} * 1000}")
    public void outboxLoop() {
        if (!"outbox".equals(strategy())) return;
        try {
            Map<String, Integer> r = processOutboxOnce();
            if (r.get("processed") > 0 || r.get("failed") > 0) log.info("outbox cycle {}", r);
        } catch (Exception e) {
            log.warn("outbox cycle failed: {}", e.getMessage());
        }
    }

    /** polling worker. */
    @Scheduled(fixedDelayString = "#{${app.polling-interval-seconds:15} * 1000}")
    public void pollingLoop() {
        if (!"polling".equals(strategy())) return;
        try {
            runPollingCycle();
        } catch (Exception e) {
            log.warn("polling cycle failed: {}", e.getMessage());
        }
    }
}
