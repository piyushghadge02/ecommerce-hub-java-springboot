package com.ecommerce.service;

import com.ecommerce.model.Order;
import com.ecommerce.model.OrderItem;
import com.ecommerce.model.Outbox;
import com.ecommerce.repository.OrderItemRepository;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.repository.OutboxRepository;
import com.ecommerce.service.Money.OrderLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Transactional write paths. Every method below runs inside ONE database
 *  transaction: any failure rolls back order + items + outbox together. */
@Service
public class OrderTxService {

    private static final Logger log = LoggerFactory.getLogger(OrderTxService.class);

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OutboxRepository outbox;

    public OrderTxService(OrderRepository orders, OrderItemRepository items,
                          OutboxRepository outbox) {
        this.orders = orders;
        this.items = items;
        this.outbox = outbox;
    }

    /** Insert order + items (+ optional outbox row) atomically.
     *  @return the persisted order, or empty when the idempotency key replayed. */
    @Transactional
    public Optional<Order> createOrder(Long userId, List<OrderLine> lines,
                                       String idempotencyKey,
                                       Function<Long, Map<String, Object>> outboxEventFactory,
                                       OffsetDateTime orderDate, OffsetDateTime updatedAt) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Order> existing = orders.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return Optional.empty();
        }
        OffsetDateTime now = Money.nowUtc();
        BigDecimal total = BigDecimal.ZERO;
        for (OrderLine l : lines) total = total.add(l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())));
        total = total.setScale(2, java.math.RoundingMode.HALF_UP);

        Order order = new Order();
        order.setUserId(userId);
        order.setOrderDate(orderDate != null ? orderDate : now);
        order.setStatus("PENDING");
        order.setTotalAmount(total);
        order.setUpdatedAt(updatedAt != null ? updatedAt : now);
        order.setIdempotencyKey(idempotencyKey);
        order = orders.saveAndFlush(order);

        List<OrderItem> rows = new ArrayList<>();
        for (OrderLine l : lines) {
            OrderItem it = new OrderItem();
            it.setOrderId(order.getId());
            it.setProductId(l.productId());
            it.setTitle(l.title());
            it.setQuantity(l.quantity());
            it.setUnitPrice(l.unitPrice());
            rows.add(it);
        }
        items.saveAll(rows);

        if (outboxEventFactory != null) {
            insertOutbox(order.getId(), outboxEventFactory.apply(order.getId()));
        }
        log.info("order created id={} total={}", order.getId(), total);
        return Optional.of(order);
    }

    /** Update status + updated_at (+ optional atomic outbox row). */
    @Transactional
    public Order updateStatus(Long orderId, String status,
                              BiFunction<Long, OffsetDateTime, Map<String, Object>> outboxEventFactory) {
        Order order = orders.findById(orderId).orElse(null);
        if (order == null) return null;
        order.setStatus(status);
        order.setUpdatedAt(Money.nowUtc());
        order = orders.saveAndFlush(order);
        if (outboxEventFactory != null) {
            insertOutbox(order.getId(), outboxEventFactory.apply(order.getId(), order.getUpdatedAt()));
        }
        log.info("order status updated id={} status={}", order.getId(), status);
        return order;
    }

    /** Outbox insert used by the dual-write fallback path (own transaction). */
    @Transactional
    public void insertOutboxFallback(Long orderId, String eventType, Map<String, Object> doc) {
        insertOutbox(orderId, Map.of("event_type", eventType, "payload", doc));
    }

    private void insertOutbox(Long orderId, Map<String, Object> event) {
        Outbox ob = new Outbox();
        ob.setOrderId(orderId);
        ob.setEventType((String) event.get("event_type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        ob.setPayload(payload);
        ob.setStatus("pending");
        ob.setAttempts(0);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ob.setNextAttemptAt(now);
        ob.setCreatedAt(now);
        outbox.save(ob);
    }
}
