package com.ecommerce.service;

import com.ecommerce.config.AppProperties;
import com.ecommerce.dto.Dtos;
import com.ecommerce.model.Order;
import com.ecommerce.model.OrderItem;
import com.ecommerce.model.User;
import com.ecommerce.mongo.ProductRepository;
import com.ecommerce.repository.OrderItemRepository;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.repository.UserRepository;
import com.ecommerce.service.Money.OrderLine;
import com.ecommerce.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Checkout + order detail + status update orchestration.
 *  Write-path discipline: checkout validates against live Mongo, persists in
 *  ONE PostgreSQL transaction, and syncs to ES only after commit. */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final List<String> STATUSES = List.of("PENDING", "PROCESSING", "SHIPPED");

    private final OrderTxService tx;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final UserRepository users;
    private final ProductRepository products;
    private final SearchService search;
    private final AppProperties props;

    public OrderService(OrderTxService tx, OrderRepository orders, OrderItemRepository items,
                        UserRepository users, ProductRepository products,
                        SearchService search, AppProperties props) {
        this.tx = tx;
        this.orders = orders;
        this.items = items;
        this.users = users;
        this.products = products;
        this.search = search;
        this.props = props;
    }

    // ------------------------------------------------------------ checkout ---
    public record PlacedOrder(Dtos.OrderCreateResponse body, int statusCode) {}

    public PlacedOrder placeOrder(Dtos.OrderCreateRequest payload) {
        User user = users.findById(payload.userId())
                .orElseThrow(() -> ApiException.badRequest("unknown user_id: " + payload.userId()));

        // Idempotency replay (before product validation: the order already exists).
        if (payload.idempotencyKey() != null && !payload.idempotencyKey().isBlank()) {
            Optional<Order> existing = orders.findByIdempotencyKey(payload.idempotencyKey());
            if (existing.isPresent()) {
                Order o = existing.get();
                boolean esHas = esHasDoc(o.getId());
                log.info("idempotent replay order_id={}", o.getId());
                return new PlacedOrder(orderOut(o, esSync(esHas ? "synced" : "queued")), 200);
            }
        }

        List<OrderLine> lines = validateLines(payload.items());
        OffsetDateTime now = Money.nowUtc();

        String strategy = props.getSyncStrategy();
        boolean useOutbox = "outbox".equals(strategy);
        Map<String, Object> docSeed = buildDocSeed(user, lines, "PENDING", now, now);

        Optional<Order> created = tx.createOrder(
                payload.userId(), lines, payload.idempotencyKey(),
                useOutbox ? (oid -> Map.of("event_type", "order.created",
                        "payload", withOrderId(docSeed, oid))) : null,
                now, now);
        Order order = created.orElseThrow(() ->
                new IllegalStateException("idempotency race: order already exists"));

        Map<String, Object> doc = withOrderId(docSeed, order.getId());
        Dtos.EsSyncDto esSync = syncAfterCommit(order.getId(), "order.created", doc);
        return new PlacedOrder(orderOut(order, esSync), 201);
    }

    private List<OrderLine> validateLines(List<Dtos.OrderItemCreate> items) {
        List<OrderLine> lines = new ArrayList<>();
        for (Dtos.OrderItemCreate item : items) {
            Map<String, Object> p = products.findById(item.productId());
            if (p == null) throw ApiException.unprocessable("product not found: " + item.productId());
            if (!Boolean.TRUE.equals(p.get("active")))
                throw ApiException.unprocessable("product is inactive: " + item.productId());
            lines.add(new OrderLine(item.productId(), (String) p.get("title"),
                    item.quantity(), Money.money((Number) p.get("price"))));
        }
        return lines;
    }

    private Map<String, Object> buildDocSeed(User user, List<OrderLine> lines,
                                             String status, OffsetDateTime orderDate,
                                             OffsetDateTime updatedAt) {
        // Build via a transient order-like structure; order_id filled in later.
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("order_date", Money.truncateToSecond(orderDate).toString());
        doc.put("status", status);
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> itemDocs = new ArrayList<>();
        for (OrderLine l : lines) {
            total = total.add(l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())));
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("product_id", l.productId());
            im.put("title", l.title());
            im.put("quantity", l.quantity());
            im.put("unit_price", l.unitPrice().doubleValue());
            itemDocs.add(im);
        }
        doc.put("total_amount", total.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue());
        doc.put("updated_at", Money.truncateToSecond(updatedAt).toString());
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", user.getId());
        customer.put("name", user.getName());
        customer.put("email", user.getEmail());
        doc.put("customer", customer);
        doc.put("items", itemDocs);
        return doc;
    }

    private Map<String, Object> withOrderId(Map<String, Object> seed, Long orderId) {
        Map<String, Object> doc = new LinkedHashMap<>(seed);
        doc.put("order_id", orderId);
        return doc;
    }

    /** Sync a committed order to ES per SYNC_STRATEGY. Never throws. */
    private Dtos.EsSyncDto syncAfterCommit(long orderId, String eventType,
                                          Map<String, Object> doc) {
        String strategy = props.getSyncStrategy();
        if ("outbox".equals(strategy) || "polling".equals(strategy)) {
            return esSync("queued");
        }
        // dual_write: index immediately; on failure enqueue repair row separately.
        try {
            search.upsertOrderDoc(doc);
            return esSync("synced");
        } catch (Exception e) {
            log.warn("dual-write to ES failed; enqueueing outbox repair order_id={}: {}",
                    orderId, e.getMessage());
            try {
                tx.insertOutboxFallback(orderId, eventType, doc);
            } catch (Exception ex) {
                log.error("outbox fallback insert failed", ex);
            }
            return esSync("pending_retry");
        }
    }

    private Dtos.EsSyncDto esSync(String status) {
        return new Dtos.EsSyncDto(props.getSyncStrategy(), status);
    }

    private boolean esHasDoc(long orderId) {
        try {
            return search.getOrderDoc(orderId) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private Dtos.OrderCreateResponse orderOut(Order order, Dtos.EsSyncDto esSync) {
        List<OrderItem> its = items.findByOrderIdOrderByIdAsc(order.getId());
        List<Dtos.OrderItemDto> dtos = its.stream()
                .map(i -> new Dtos.OrderItemDto(i.getProductId(), i.getTitle(), i.getQuantity(),
                        i.getUnitPrice(), Money.lineTotal(i.getUnitPrice(), i.getQuantity())))
                .toList();
        return new Dtos.OrderCreateResponse(order.getId(), order.getUserId(), order.getStatus(),
                order.getTotalAmount(), order.getOrderDate(), order.getUpdatedAt(), dtos, esSync);
    }

    // -------------------------------------------------------------- detail ---
    public Dtos.OrderDetailResponse getOrderDetail(long orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("order not found: " + orderId));
        User user = users.findById(order.getUserId()).orElseThrow();
        List<OrderItem> its = items.findByOrderIdOrderByIdAsc(orderId);

        Boolean esInSync;
        OffsetDateTime esUpdatedAt = null;
        Map<String, Object> esDoc;
        try {
            esDoc = search.getOrderDoc(orderId);
        } catch (Exception e) {
            log.warn("es unreachable for sync check: {}", e.getMessage());
            esDoc = null;
            esInSync = null;
            return detailOut(order, user, its, esInSync, esUpdatedAt);
        }
        if (esDoc == null) {
            esInSync = false;
        } else {
            esUpdatedAt = SearchService.coerceDt(esDoc.get("updated_at"));
            esInSync = Money.truncateToSecond(esUpdatedAt)
                    .isEqual(Money.truncateToSecond(order.getUpdatedAt()))
                    && String.valueOf(esDoc.get("status")).equals(order.getStatus())
                    && ((Number) esDoc.getOrDefault("total_amount", 0.0)).doubleValue()
                        == order.getTotalAmount().doubleValue();
        }
        return detailOut(order, user, its, esInSync, esUpdatedAt);
    }

    private Dtos.OrderDetailResponse detailOut(Order order, User user, List<OrderItem> its,
                                               Boolean esInSync, OffsetDateTime esUpdatedAt) {
        List<Dtos.OrderItemDto> dtos = its.stream()
                .map(i -> new Dtos.OrderItemDto(i.getProductId(), i.getTitle(), i.getQuantity(),
                        i.getUnitPrice(), Money.lineTotal(i.getUnitPrice(), i.getQuantity())))
                .toList();
        return new Dtos.OrderDetailResponse(order.getId(), order.getUserId(),
                new Dtos.CustomerDto(user.getId(), user.getName(), user.getEmail()),
                order.getStatus(), order.getTotalAmount(), order.getOrderDate(),
                order.getUpdatedAt(), dtos,
                new Dtos.SyncInfoDto(esInSync, order.getUpdatedAt(), esUpdatedAt));
    }

    // --------------------------------------------------------------- status ---
    public Dtos.StatusUpdateResponse updateOrderStatus(long orderId, String status) {
        if (!STATUSES.contains(status)) throw ApiException.unprocessable("invalid status: " + status);
        Order existing = orders.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("order not found: " + orderId));
        User user = users.findById(existing.getUserId()).orElseThrow();
        List<OrderItem> its = items.findByOrderIdOrderByIdAsc(orderId);

        String strategy = props.getSyncStrategy();
        boolean useOutbox = "outbox".equals(strategy);
        Order order = tx.updateStatus(orderId, status, useOutbox
                ? (oid, updatedAt) -> Map.of("event_type", "order.status_updated",
                        "payload", buildStatusDoc(oid, user, its, existing.getOrderDate(), status, updatedAt))
                : null);

        Map<String, Object> doc = buildStatusDoc(order.getId(), user, its,
                existing.getOrderDate(), status, order.getUpdatedAt());
        Dtos.EsSyncDto esSync = syncAfterCommit(order.getId(), "order.status_updated", doc);
        return new Dtos.StatusUpdateResponse(order.getId(), order.getStatus(),
                order.getUpdatedAt(), esSync);
    }

    private Map<String, Object> buildStatusDoc(long orderId, User user, List<OrderItem> its,
                                               OffsetDateTime orderDate, String status,
                                               OffsetDateTime updatedAt) {
        List<OrderLine> lines = its.stream()
                .map(i -> new OrderLine(i.getProductId(), i.getTitle(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return withOrderId(buildDocSeed(user, lines, status, orderDate, updatedAt), orderId);
    }
}
