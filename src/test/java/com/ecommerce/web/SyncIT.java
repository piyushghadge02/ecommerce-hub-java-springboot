package com.ecommerce.web;

import com.ecommerce.model.Order;
import com.ecommerce.model.User;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.repository.OutboxRepository;
import com.ecommerce.repository.UserRepository;
import com.ecommerce.service.Money;
import com.ecommerce.service.OrderService;
import com.ecommerce.service.OrderTxService;
import com.ecommerce.service.SearchService;
import com.ecommerce.service.SyncService;
import com.ecommerce.dto.Dtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Sync strategies against live ES (orders_test index). */
class SyncIT extends AbstractIT {

    @Autowired
    private UserRepository users;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private OrderTxService orderTx;
    @Autowired
    private OrderService orderService;
    @Autowired
    private SyncService sync;

    private User user;
    private String productId;

    @BeforeEach
    void seed() {
        User u = new User();
        u.setName("Sync User");
        u.setEmail("sync." + System.nanoTime() + "@example.com");
        user = users.save(u);
        productId = (String) products.create(Map.of(
                "sku", "SY-" + System.nanoTime(), "title", "Sync Widget", "description", "s",
                "price", 25.00, "category", "office", "tags", List.of(),
                "attributes", Map.of(), "variants", List.of(Map.of("sku", "v")),
                "active", true)).get("id");
    }

    private Order createOrderDirect() {
        return orderTx.createOrder(user.getId(),
                List.of(new Money.OrderLine(productId, "Sync Widget", 2,
                        new BigDecimal("25.00"))),
                null, null, null, null).orElseThrow();
    }

    @Test
    void outboxWorkerDrainsToEs() throws Exception {
        Order o = createOrderDirect();
        // simulate the outbox strategy: insert the event row, then run the worker
        Map<String, Object> doc = SearchService.buildOrderDoc(o, user,
                List.of(itemOf(o.getId())));
        orderTx.insertOutboxFallback(o.getId(), "order.created", doc);

        assertNull(search.getOrderDoc("orders_test", o.getId()));
        Map<String, Integer> result = sync.processOutboxOnce();
        assertEquals(1, result.get("processed"));

        Map<String, Object> indexed = search.getOrderDoc("orders_test", o.getId());
        assertNotNull(indexed);
        assertEquals("PENDING", indexed.get("status"));
        assertTrue(outbox.findAll().stream().allMatch(r -> "done".equals(r.getStatus())));
    }

    @Test
    void outboxBackoffMarksFailedAfterMaxAttempts() {
        Order o = createOrderDirect();
        // Use a malformed document that ES will reject (invalid date format)
        Map<String, Object> doc = SearchService.buildOrderDoc(o, user,
                List.of(itemOf(o.getId())));
        doc.put("order_date", "not-a-date");
        orderTx.insertOutboxFallback(o.getId(), "order.created", doc);

        // max attempts = 3 in test profile; reset next_attempt_at to simulate time passing
        for (int i = 0; i < 3; i++) {
            sync.processOutboxOnce();
            // make the row due again for the next attempt
            outbox.findAll().forEach(r -> {
                r.setNextAttemptAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
                outbox.save(r);
            });
            outbox.flush();
        }
        assertTrue(outbox.findAll().stream().allMatch(r -> "failed".equals(r.getStatus())));
    }

    @Test
    void pollingCycleSyncsByWatermark() throws Exception {
        Order o1 = createOrderDirect();
        sync.runPollingCycle();
        assertNotNull(search.getOrderDoc("orders_test", o1.getId()));
        assertNotNull(sync.getWatermark());

        // second cycle with no changes syncs nothing
        Map<String, Object> r = sync.runPollingCycle();
        assertEquals(0, r.get("synced"));

        // a status change moves updated_at -> picked up by next cycle
        orderTx.updateStatus(o1.getId(), "SHIPPED", null);
        sync.runPollingCycle();
        assertEquals("SHIPPED", search.getOrderDoc("orders_test", o1.getId()).get("status"));
    }

    @Test
    void reindexRebuildsFromPostgres() throws Exception {
        Order o1 = createOrderDirect();
        Order o2 = createOrderDirect();
        search.deleteIndex("orders_test");
        long n = sync.reindexAll("orders_test");
        assertEquals(2, n);
        assertNotNull(search.getOrderDoc("orders_test", o1.getId()));
        assertNotNull(search.getOrderDoc("orders_test", o2.getId()));
    }

    @Test
    void deterministicDocIdMakesUpsertsIdempotent() throws Exception {
        Order o = createOrderDirect();
        Map<String, Object> doc = SearchService.buildOrderDoc(o, user,
                List.of(itemOf(o.getId())));
        search.upsertOrderDoc("orders_test", doc);
        search.upsertOrderDoc("orders_test", doc); // replay
        var client = applicationContext.getBean(
                co.elastic.clients.elasticsearch.ElasticsearchClient.class);
        client.indices().refresh(r -> r.index("orders_test"));
        long count = client.count(c -> c.index("orders_test")).count();
        assertEquals(1, count);
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;
    @Autowired
    private com.ecommerce.repository.OrderItemRepository itemRepo;

    private com.ecommerce.model.OrderItem itemOf(long orderId) {
        return itemRepo.findByOrderIdOrderByIdAsc(orderId).get(0);
    }

    @Test
    void adminSyncStatusEndpoint() throws Exception {
        Order o = createOrderDirect();
        Map<String, Object> doc = SearchService.buildOrderDoc(o, user,
                List.of(itemOf(o.getId())));
        orderTx.insertOutboxFallback(o.getId(), "order.created", doc);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/sync-status"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.outbox_pending",
                                org.hamcrest.Matchers.is(1)));
    }

    @Test
    void healthEndpoint() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/health"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status", org.hamcrest.Matchers.is("ok")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.sync_strategy",
                                org.hamcrest.Matchers.is("dual_write")));
    }
}
