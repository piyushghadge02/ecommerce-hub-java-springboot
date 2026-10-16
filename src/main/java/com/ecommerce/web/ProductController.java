package com.ecommerce.web;

import com.ecommerce.dto.Dtos;
import com.ecommerce.mongo.ProductRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "true") String active) {
        Boolean activeFilter = switch (active) {
            case "true" -> true;
            case "false" -> false;
            case "all" -> null;
            default -> throw ApiException.unprocessable("invalid active value: " + active);
        };
        return products.list(category, q, activeFilter);
    }

    @GetMapping("/{productId}")
    public Map<String, Object> get(@PathVariable String productId) {
        Map<String, Object> doc = products.findById(productId);
        if (doc == null) throw ApiException.notFound("product not found: " + productId);
        return doc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody Dtos.ProductCreateRequest payload) {
        validateVariants(payload.variants());
        if (payload.price().compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.unprocessable("price must be > 0");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("sku", payload.sku());
        doc.put("title", payload.title());
        doc.put("description", payload.description());
        doc.put("price", payload.price().doubleValue());
        doc.put("category", payload.category());
        doc.put("tags", payload.tags() == null ? List.of() : payload.tags());
        doc.put("attributes", payload.attributes() == null ? Map.of() : payload.attributes());
        doc.put("variants", payload.variants());
        doc.put("active", payload.active() == null || payload.active());
        try {
            return products.create(doc);
        } catch (ProductRepository.DuplicateSkuException e) {
            throw ApiException.conflict(e.getMessage());
        }
    }

    @PutMapping("/{productId}")
    public Map<String, Object> update(@PathVariable String productId,
                                      @RequestBody Map<String, Object> patch) {
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (e.getValue() != null) clean.put(e.getKey(), e.getValue());
        }
        clean.remove("id");
        clean.remove("_id");
        if (clean.isEmpty()) throw ApiException.unprocessable("empty update");
        Object price = clean.get("price");
        if (price != null && new BigDecimal(price.toString()).compareTo(BigDecimal.ZERO) <= 0)
            throw ApiException.unprocessable("price must be > 0");
        Object title = clean.get("title");
        if (title != null && title.toString().isBlank())
            throw ApiException.unprocessable("title must not be blank");
        Object sku = clean.get("sku");
        if (sku != null && sku.toString().isBlank())
            throw ApiException.unprocessable("sku must not be blank");
        if (clean.containsKey("variants")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> variants = (List<Map<String, Object>>) clean.get("variants");
            validateVariants(variants);
        }
        try {
            Map<String, Object> doc = products.update(productId, clean);
            if (doc == null) throw ApiException.notFound("product not found: " + productId);
            return doc;
        } catch (ProductRepository.DuplicateSkuException e) {
            throw ApiException.conflict(e.getMessage());
        }
    }

    private void validateVariants(List<Map<String, Object>> variants) {
        if (variants == null || variants.isEmpty())
            throw ApiException.unprocessable("at least one variant is required");
        for (Map<String, Object> v : variants) {
            Object sku = v.get("sku");
            if (sku == null || sku.toString().isBlank())
                throw ApiException.unprocessable("variant sku is required");
        }
    }
}
