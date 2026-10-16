package com.ecommerce.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Request/response DTOs. JSON uses snake_case (global naming strategy);
 *  money (BigDecimal) serializes as JSON strings, like the Python API. */
public final class Dtos {

    private Dtos() {}

    public record UserDto(Long id, String name, String email) {}

    public record OrderItemCreate(
            @NotBlank(message = "product_id is required") String productId,
            @NotNull(message = "quantity is required")
            @Min(value = 1, message = "quantity must be >= 1") Integer quantity) {}

    public record OrderCreateRequest(
            @NotNull(message = "user_id is required") Long userId,
            @NotEmpty(message = "items must not be empty")
            @Valid List<OrderItemCreate> items,
            String idempotencyKey) {}

    public record OrderItemDto(String productId, String title, int quantity,
                               BigDecimal unitPrice, BigDecimal lineTotal) {}

    public record EsSyncDto(String strategy, String status) {}

    public record OrderCreateResponse(Long orderId, Long userId, String status,
                                      BigDecimal totalAmount, OffsetDateTime orderDate,
                                      OffsetDateTime updatedAt, List<OrderItemDto> items,
                                      EsSyncDto esSync) {}

    public record CustomerDto(Long id, String name, String email) {}

    public record SyncInfoDto(Boolean esInSync, OffsetDateTime pgUpdatedAt,
                              OffsetDateTime esUpdatedAt) {}

    public record OrderDetailResponse(Long orderId, Long userId, CustomerDto customer,
                                      String status, BigDecimal totalAmount,
                                      OffsetDateTime orderDate, OffsetDateTime updatedAt,
                                      List<OrderItemDto> items, SyncInfoDto sync) {}

    public record StatusUpdateRequest(String status) {}

    public record StatusUpdateResponse(Long orderId, String status,
                                       OffsetDateTime updatedAt, EsSyncDto esSync) {}

    public record SearchRequest(
            String q,
            List<String> statuses,
            LocalDate dateFrom,
            LocalDate dateTo,
            Double minTotal,
            Double maxTotal,
            String sortBy,
            String sortDir,
            @Min(1) int page,
            @Min(1) @jakarta.validation.constraints.Max(100) int pageSize) {
        public SearchRequest {
            if (sortBy == null) sortBy = "order_date";
            if (sortDir == null) sortDir = "desc";
            if (page == 0) page = 1;
            if (pageSize == 0) pageSize = 20;
        }
    }

    public record SearchOrderRow(Long orderId, OffsetDateTime orderDate,
                                 CustomerDto customer, Double totalAmount, String status) {}

    public record SearchAggs(double revenue, Map<String, Integer> byStatus, int totalOrders) {}

    public record SearchResponse(long total, int page, int pageSize, int totalPages,
                                 List<SearchOrderRow> orders, SearchAggs aggs) {}

    public record HealthResponse(String status, String postgres, String mongodb,
                                 String elasticsearch, String syncStrategy) {}

    public record SyncStatusResponse(long outboxPending, long outboxFailed,
                                     String pollingWatermark, Long elasticsearchDocs) {}

    public record ReindexResponse(long indexed) {}

    /** Product create payload. Variants are open maps (extra attrs preserved). */
    public record ProductCreateRequest(
            @NotBlank(message = "sku is required") String sku,
            @NotBlank(message = "title is required") String title,
            String description,
            @NotNull(message = "price is required") BigDecimal price,
            @NotBlank(message = "category is required") String category,
            List<String> tags,
            Map<String, Object> attributes,
            @NotEmpty(message = "at least one variant is required")
            List<Map<String, Object>> variants,
            Boolean active) {}
}
