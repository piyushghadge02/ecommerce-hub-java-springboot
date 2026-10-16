package com.ecommerce.service;

import com.ecommerce.model.Order;
import com.ecommerce.model.OrderItem;
import com.ecommerce.model.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for ES document building and money helpers. */
class EsDocBuilderTest {

    @Test
    @SuppressWarnings("unchecked")
    void buildOrderDocSnapshots() {
        User u = new User();
        u.setId(3L); u.setName("Wendy Wireless"); u.setEmail("wendy.wireless@example.com");
        Order o = new Order();
        o.setId(42L); o.setUserId(3L); o.setStatus("PENDING");
        o.setTotalAmount(new BigDecimal("150.50"));
        o.setOrderDate(OffsetDateTime.of(2026, 7, 9, 19, 0, 0, 123_000_000, ZoneOffset.UTC));
        o.setUpdatedAt(OffsetDateTime.of(2026, 7, 9, 19, 5, 0, 0, ZoneOffset.UTC));
        OrderItem i1 = new OrderItem();
        i1.setProductId("abc123"); i1.setTitle("Wireless Mouse");
        i1.setQuantity(1); i1.setUnitPrice(new BigDecimal("50.16"));
        OrderItem i2 = new OrderItem();
        i2.setProductId("def456"); i2.setTitle("Mechanical Keyboard");
        i2.setQuantity(1); i2.setUnitPrice(new BigDecimal("100.34"));

        Map<String, Object> doc = SearchService.buildOrderDoc(o, u, List.of(i1, i2));

        assertEquals(42L, doc.get("order_id"));
        assertEquals("PENDING", doc.get("status"));
        assertEquals(150.50, (Double) doc.get("total_amount"), 0.001);
        // truncated to seconds
        assertEquals("2026-07-09T19:00:00Z", doc.get("order_date"));
        assertEquals("2026-07-09T19:05:00Z", doc.get("updated_at"));
        Map<String, Object> customer = (Map<String, Object>) doc.get("customer");
        assertEquals("Wendy Wireless", customer.get("name"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) doc.get("items");
        assertEquals(2, items.size());
        assertEquals("Wireless Mouse", items.get(0).get("title"));
        assertEquals(50.16, (Double) items.get(0).get("unit_price"), 0.001);
        assertEquals(SearchService.docId(42L), "order-42");
    }

    @Test
    void moneyRoundsHalfUp() {
        assertEquals(new BigDecimal("50.16"), Money.money(50.16));
        assertEquals(new BigDecimal("50.17"), Money.money(50.165));
        assertEquals(new BigDecimal("100.32"),
                Money.lineTotal(new BigDecimal("50.16"), 2));
        assertEquals(0, Money.truncateToSecond(
                OffsetDateTime.now(ZoneOffset.UTC)).getNano());
    }
}
