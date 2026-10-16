package com.ecommerce.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Products API: MongoDB-only catalog behavior. */
class ProductApiIT extends AbstractIT {

    private Map<String, Object> doc(String sku, String title, double price,
                                    String category, boolean active) {
        return Map.of(
                "sku", sku, "title", title, "description", title + " desc",
                "price", price, "category", category,
                "tags", List.of("wireless"),
                "attributes", Map.of("color", "black"),
                "variants", List.of(Map.of("sku", sku + "-V", "color", "black", "stock", 5)),
                "active", active);
    }

    private void seedProducts() {
        products.create(doc("WM-001", "Wireless Mouse", 50.16, "peripherals", true));
        products.create(doc("EB-301", "Wireless Earbuds", 79.99, "audio", true));
        products.create(doc("CRT-9", "Old CRT", 99.99, "office", false));
    }

    @Test
    void listHidesInactiveByDefault() throws Exception {
        seedProducts();
        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].title", everyItem(not(containsString("CRT")))));
    }

    @Test
    void listActiveAllShowsInactive() throws Exception {
        seedProducts();
        mvc.perform(get("/api/products").param("active", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/products").param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is("Old CRT")));
    }

    @Test
    void listFiltersByCategoryAndQ() throws Exception {
        seedProducts();
        mvc.perform(get("/api/products").param("category", "audio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is("Wireless Earbuds")));
        mvc.perform(get("/api/products").param("q", "MOUSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getByIdAnd404() throws Exception {
        seedProducts();
        String id = (String) products.list(null, "Wireless Mouse", true).get(0).get("id");
        mvc.perform(get("/api/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Wireless Mouse")));
        mvc.perform(get("/api/products/000000000000000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", containsString("product not found")));
    }

    @Test
    void createValidatesAndReturns201() throws Exception {
        mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(doc("N-1", "New Thing", 10.0, "cables", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku", is("N-1")))
                .andExpect(jsonPath("$.active", is(true)));
    }

    @Test
    void createRejectsBadPayload() throws Exception {
        // missing title
        mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"X\",\"price\":5,\"category\":\"c\"," +
                                "\"variants\":[{\"sku\":\"X-V\"}]}"))
                .andExpect(status().is(422));
        // non-positive price
        Map<String, Object> bad = new java.util.HashMap<>(doc("X", "T", -5, "c", true));
        mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().is(422));
        // no variants
        Map<String, Object> bad2 = new java.util.HashMap<>(doc("X", "T", 5, "c", true));
        bad2.put("variants", List.of());
        mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad2)))
                .andExpect(status().is(422));
    }

    @Test
    void createDuplicateSkuIs409() throws Exception {
        seedProducts();
        mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(doc("WM-001", "Dup", 10.0, "cables", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("duplicate sku")));
    }

    @Test
    void updateMergesAndValidates() throws Exception {
        seedProducts();
        String id = (String) products.list(null, "Wireless Mouse", true).get(0).get("id");
        mvc.perform(put("/api/products/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\": 59.99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price", is(59.99)))
                .andExpect(jsonPath("$.title", is("Wireless Mouse")));
        // empty patch -> 422
        mvc.perform(put("/api/products/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(422));
        // unknown id -> 404
        mvc.perform(put("/api/products/000000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\": 1}"))
                .andExpect(status().isNotFound());
    }
}
