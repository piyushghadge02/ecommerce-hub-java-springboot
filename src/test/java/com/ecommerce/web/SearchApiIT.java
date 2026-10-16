package com.ecommerce.web;

import com.ecommerce.model.User;
import com.ecommerce.repository.UserRepository;
import com.ecommerce.service.OrderService;
import com.ecommerce.dto.Dtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Search API: Elasticsearch-only queries, filters, aggs, pagination. */
class SearchApiIT extends AbstractIT {

    @Autowired
    private UserRepository users;
    @Autowired
    private OrderService orderService;

    private User wendy, john;
    private String mouseId, kbId, lampId;

    @BeforeEach
    void seed() throws Exception {
        wendy = users.save(newUser("Wendy Wireless", "wendy.wireless@example.com"));
        john = users.save(newUser("John Doe", "john.doe@example.com"));
        mouseId = product("WM-S", "Wireless Mouse", 50.16, "peripherals");
        kbId = product("MK-S", "Mechanical Keyboard", 100.34, "peripherals");
        lampId = product("LP-S", "Desk Lamp LED", 32.50, "office");

        // wendy: 2 orders with wireless mouse (PENDING)
        place(wendy.getId(), mouseId, 1);
        place(wendy.getId(), mouseId, 2);
        // john: 1 order with keyboard (SHIPPED via status update)
        long oid = place(john.getId(), kbId, 1);
        orderService.updateOrderStatus(oid, "SHIPPED");
        // john: 1 order with lamp (PROCESSING)
        long oid2 = place(john.getId(), lampId, 1);
        orderService.updateOrderStatus(oid2, "PROCESSING");
        // refresh for consistent search results
        search.ensureIndex("orders_test");
        var client = applicationContext.getBean(co.elastic.clients.elasticsearch.ElasticsearchClient.class);
        client.indices().refresh(r -> r.index("orders_test"));
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    private User newUser(String name, String email) {
        User u = new User();
        u.setName(name);
        u.setEmail(email + "." + System.nanoTime());
        return u;
    }

    private String product(String sku, String title, double price, String category) {
        return (String) products.create(Map.of(
                "sku", sku + System.nanoTime(), "title", title, "description", "d",
                "price", price, "category", category, "tags", List.of(),
                "attributes", Map.of(), "variants", List.of(Map.of("sku", "v")),
                "active", true)).get("id");
    }

    private long place(long userId, String productId, int qty) {
        OrderService.PlacedOrder p = orderService.placeOrder(new Dtos.OrderCreateRequest(
                userId, List.of(new Dtos.OrderItemCreate(productId, qty)), null));
        assert p.statusCode() == 201;
        return p.body().orderId();
    }

    private String searchBody(String json) {
        return json;
    }

    @Test
    void qMatchesCustomerNameAndItemTitle() throws Exception {
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"q\":\"wireless\",\"page_size\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(2)))
                .andExpect(jsonPath("$.orders[*].customer.name",
                        everyItem(containsString("Wendy"))));
        // partial product title
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"q\":\"Keyboard\",\"page_size\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)));
    }

    @Test
    void statusFilterNarrows() throws Exception {
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"statuses\":[\"PENDING\"],\"page_size\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(2)));
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"statuses\":[\"SHIPPED\"],\"page_size\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)));
    }

    @Test
    void priceAndDateFilters() throws Exception {
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"min_total\":200,\"page_size\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(0)));
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"min_total\":90,\"max_total\":110,\"page_size\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(2)));
        // date range covering today
        String today = java.time.LocalDate.now().toString();
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"date_from\":\"" + today
                                + "\",\"date_to\":\"" + today + "\",\"page_size\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(4)));
    }

    @Test
    void aggsTrackActiveFilters() throws Exception {
        // revenue over all 4: 50.16 + 100.32 + 100.34 + 32.50 = 283.32
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"page_size\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggs.revenue", closeTo(283.32, 0.01)))
                .andExpect(jsonPath("$.aggs.total_orders", is(4)))
                .andExpect(jsonPath("$.aggs.by_status.PENDING", is(2)))
                .andExpect(jsonPath("$.aggs.by_status.SHIPPED", is(1)))
                .andExpect(jsonPath("$.aggs.by_status.PROCESSING", is(1)));
        // filtered revenue: only PENDING (50.16 + 100.32 = 150.48)
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"statuses\":[\"PENDING\"],\"page_size\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggs.revenue", closeTo(150.48, 0.01)))
                .andExpect(jsonPath("$.aggs.total_orders", is(2)));
    }

    @Test
    void paginationAndSorting() throws Exception {
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"page\":1,\"page_size\":2,\"sort_by\":\"total_amount\",\"sort_dir\":\"asc\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(4)))
                .andExpect(jsonPath("$.total_pages", is(2)))
                .andExpect(jsonPath("$.orders[0].total_amount", is(32.5)))
                .andExpect(jsonPath("$.orders[1].total_amount", is(50.16)));
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"page\":2,\"page_size\":2,\"sort_by\":\"total_amount\",\"sort_dir\":\"asc\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders", hasSize(2)));
    }

    @Test
    void invalidSortIs422() throws Exception {
        mvc.perform(post("/api/search/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchBody("{\"sort_by\":\"nope\"}")))
                .andExpect(status().is(422));
    }
}
