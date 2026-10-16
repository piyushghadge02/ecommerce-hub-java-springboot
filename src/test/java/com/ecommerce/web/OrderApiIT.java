package com.ecommerce.web;

import com.ecommerce.model.Order;
import com.ecommerce.model.User;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.repository.UserRepository;
import com.ecommerce.service.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Orders API: transactional checkout, idempotency, rollback, status updates. */
class OrderApiIT extends AbstractIT {

    @Autowired
    private UserRepository users;
    @Autowired
    private OrderRepository orders;

    private User user;
    private String mouseId;
    private String kbId;

    @BeforeEach
    void seed() {
        user = new User();
        user.setName("Test User");
        user.setEmail("test.user." + System.nanoTime() + "@example.com");
        user = users.save(user);
        mouseId = (String) products.create(Map.of(
                "sku", "WM-T", "title", "Wireless Mouse", "description", "m",
                "price", 50.16, "category", "peripherals", "tags", List.of("wireless"),
                "attributes", Map.of(), "variants", List.of(Map.of("sku", "WM-T-V")),
                "active", true)).get("id");
        kbId = (String) products.create(Map.of(
                "sku", "MK-T", "title", "Mechanical Keyboard", "description", "k",
                "price", 100.34, "category", "peripherals", "tags", List.of(),
                "attributes", Map.of(), "variants", List.of(Map.of("sku", "MK-T-V")),
                "active", true)).get("id");
    }

    private String orderBody(String productId, int qty, String key) {
        return "{\"user_id\":" + user.getId() + ",\"items\":[{\"product_id\":\""
                + productId + "\",\"quantity\":" + qty + "}]"
                + (key == null ? "" : ",\"idempotency_key\":\"" + key + "\"") + "}";
    }

    @Test
    void checkoutHappyPathServerPricesWin() throws Exception {
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(mouseId, 2, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order_id", notNullValue()))
                .andExpect(jsonPath("$.status", is("PENDING")))
                // 2 x 50.16 computed server-side, serialized as string
                .andExpect(jsonPath("$.total_amount", is("100.32")))
                .andExpect(jsonPath("$.items[0].line_total", is("100.32")))
                .andExpect(jsonPath("$.es_sync.status", is("synced")));

        // change the catalog price; the next checkout must use the NEW price
        products.update(mouseId, Map.of("price", 60.00));
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(mouseId, 1, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total_amount", is("60.00")));
    }

    @Test
    void checkoutValidation() throws Exception {
        // unknown product
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("000000000000000000000000", 1, null)))
                .andExpect(status().is(422));
        // inactive product
        String inactive = (String) products.create(Map.of(
                "sku", "IN-T", "title", "Inactive", "description", "i",
                "price", 9.99, "category", "cables", "tags", List.of(),
                "attributes", Map.of(), "variants", List.of(Map.of("sku", "IN-T-V")),
                "active", false)).get("id");
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(inactive, 1, null)))
                .andExpect(status().is(422));
        // bad quantity
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(mouseId, 0, null)))
                .andExpect(status().is(422));
        // empty items
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":" + user.getId() + ",\"items\":[]}"))
                .andExpect(status().is(422));
        // unknown user -> 400
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":999999,\"items\":[{\"product_id\":\""
                                + mouseId + "\",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("unknown user_id")));
    }

    @Autowired
    private com.ecommerce.service.OrderTxService orderTx;

    @Test
    void checkoutIsAtomicOnFailure() {
        // force a constraint violation inside the transaction (null title).
        // The @Transactional on orderTx.createOrder rolls back order+items together;
        // assertThrows verifies the failure, and the test's own @Transactional
        // rolls back everything so no partial rows survive.
        assertThrows(Exception.class, () ->
                orderTx.createOrder(user.getId(),
                        List.of(new Money.OrderLine(mouseId, null, 1, new BigDecimal("50.16"))),
                        null, null, null, null));
    }

    @Test
    void idempotentReplay() throws Exception {
        String key = "idem-" + System.nanoTime();
        String first = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(kbId, 1, key)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = mapper.readTree(first).get("order_id").asLong();
        long countAfterFirst = orders.count();

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(kbId, 1, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order_id", is((int) orderId)));
        assertEquals(countAfterFirst, orders.count(), "replay must not insert");
    }

    @Test
    void getDetailAnd404() throws Exception {
        String body = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(mouseId, 1, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = mapper.readTree(body).get("order_id").asLong();

        mvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.name", is("Test User")))
                .andExpect(jsonPath("$.items[0].title", is("Wireless Mouse")))
                .andExpect(jsonPath("$.sync.es_in_sync", is(true)));

        mvc.perform(get("/api/orders/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", containsString("order not found")));
    }

    @Test
    void statusUpdateFlowsToEs() throws Exception {
        String body = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(mouseId, 1, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = mapper.readTree(body).get("order_id").asLong();

        mvc.perform(patch("/api/orders/" + orderId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SHIPPED")))
                .andExpect(jsonPath("$.es_sync.status", is("synced")));

        // ES reflects the new status (dual_write is immediate)
        Map<String, Object> doc = search.getOrderDoc("orders_test", orderId);
        assertNotNull(doc);
        assertEquals("SHIPPED", doc.get("status"));

        // invalid status -> 422
        mvc.perform(patch("/api/orders/" + orderId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOPE\"}"))
                .andExpect(status().is(422));
        // unknown order -> 404
        mvc.perform(patch("/api/orders/999999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void snapshotPreservedAfterCatalogEdit() throws Exception {
        String body = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(mouseId, 1, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = mapper.readTree(body).get("order_id").asLong();

        // rename + reprice in Mongo (catalog only)
        products.update(mouseId, Map.of("title", "Wireless Mouse Pro", "price", 59.99));

        mvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title", is("Wireless Mouse")))
                .andExpect(jsonPath("$.items[0].unit_price", is("50.16")));
        Map<String, Object> doc = search.getOrderDoc("orders_test", orderId);
        assertEquals("Wireless Mouse", ((List<Map<String, Object>>) doc.get("items")).get(0).get("title"));
    }
}
