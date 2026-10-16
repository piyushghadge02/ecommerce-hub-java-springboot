package com.ecommerce.service;

import com.ecommerce.config.AppProperties;
import com.ecommerce.dto.Dtos;
import com.ecommerce.model.Order;
import com.ecommerce.model.User;
import com.ecommerce.mongo.ProductRepository;
import com.ecommerce.repository.OrderItemRepository;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** OrderService orchestration with mocked infrastructure. */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderTxService tx;
    @Mock OrderRepository orders;
    @Mock OrderItemRepository items;
    @Mock UserRepository users;
    @Mock ProductRepository products;
    @Mock SearchService search;

    AppProperties props;
    OrderService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.setSyncStrategy("dual_write");
        service = new OrderService(tx, orders, items, users, products, search, props);
    }

    private User user() {
        User u = new User();
        u.setId(1L); u.setName("John Doe"); u.setEmail("john.doe@example.com");
        return u;
    }

    private Map<String, Object> productDoc() {
        return Map.of("id", "p1", "title", "Wireless Mouse", "price", 50.16,
                "active", true);
    }

    private Order persistedOrder() {
        Order o = new Order();
        o.setId(10L); o.setUserId(1L); o.setStatus("PENDING");
        o.setTotalAmount(new BigDecimal("100.32"));
        o.setOrderDate(OffsetDateTime.now(ZoneOffset.UTC));
        o.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return o;
    }

    @Test
    void checkoutComputesServerSideTotal() {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(products.findById("p1")).thenReturn(productDoc());
        Order o = persistedOrder();
        when(tx.createOrder(eq(1L), anyList(), isNull(), isNull(), any(), any()))
                .thenReturn(Optional.of(o));
        when(items.findByOrderIdOrderByIdAsc(10L)).thenReturn(List.of());

        Dtos.OrderCreateRequest req = new Dtos.OrderCreateRequest(1L,
                List.of(new Dtos.OrderItemCreate("p1", 2)), null);
        OrderService.PlacedOrder placed = service.placeOrder(req);

        assertEquals(201, placed.statusCode());
        assertEquals("100.32", placed.body().totalAmount().toString());
        assertEquals("synced", placed.body().esSync().status());
        // server price (50.16) was used, not anything client-supplied
        ArgumentCaptor<List<Money.OrderLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(tx).createOrder(eq(1L), lines.capture(), isNull(), isNull(), any(), any());
        assertEquals(new BigDecimal("50.16"), lines.getValue().get(0).unitPrice());
    }

    @Test
    void esDownCheckoutStillCommitsWithPendingRetry() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(products.findById("p1")).thenReturn(productDoc());
        Order o = persistedOrder();
        when(tx.createOrder(eq(1L), anyList(), isNull(), isNull(), any(), any()))
                .thenReturn(Optional.of(o));
        when(items.findByOrderIdOrderByIdAsc(10L)).thenReturn(List.of());
        doThrow(new RuntimeException("connection refused")).when(search).upsertOrderDoc(anyMap());

        Dtos.OrderCreateRequest req = new Dtos.OrderCreateRequest(1L,
                List.of(new Dtos.OrderItemCreate("p1", 1)), null);
        OrderService.PlacedOrder placed = service.placeOrder(req);

        assertEquals(201, placed.statusCode());
        assertEquals("pending_retry", placed.body().esSync().status());
        assertEquals("dual_write", placed.body().esSync().strategy());
        verify(tx).insertOutboxFallback(eq(10L), eq("order.created"), anyMap());
    }

    @Test
    void idempotentReplayReturnsExistingOrder() {
        Order o = persistedOrder();
        o.setIdempotencyKey("key-123");
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(orders.findByIdempotencyKey("key-123")).thenReturn(Optional.of(o));
        when(items.findByOrderIdOrderByIdAsc(10L)).thenReturn(List.of());

        Dtos.OrderCreateRequest req = new Dtos.OrderCreateRequest(1L,
                List.of(new Dtos.OrderItemCreate("p1", 1)), "key-123");
        // getOrderDetail path needs order lookup
        OrderService.PlacedOrder placed = service.placeOrder(req);

        assertEquals(200, placed.statusCode());
        assertEquals(10L, placed.body().orderId());
        verify(tx, never()).createOrder(anyLong(), anyList(), any(), any(), any(), any());
    }

    @Test
    void unknownUserIs400() {
        when(users.findById(99L)).thenReturn(Optional.empty());
        Dtos.OrderCreateRequest req = new Dtos.OrderCreateRequest(99L,
                List.of(new Dtos.OrderItemCreate("p1", 1)), null);
        com.ecommerce.web.ApiException ex = assertThrows(
                com.ecommerce.web.ApiException.class, () -> service.placeOrder(req));
        assertEquals(400, ex.getStatus());
    }

    @Test
    void unknownProductIs422() {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(products.findById("nope")).thenReturn(null);
        Dtos.OrderCreateRequest req = new Dtos.OrderCreateRequest(1L,
                List.of(new Dtos.OrderItemCreate("nope", 1)), null);
        com.ecommerce.web.ApiException ex = assertThrows(
                com.ecommerce.web.ApiException.class, () -> service.placeOrder(req));
        assertEquals(422, ex.getStatus());
    }

    @Test
    void inactiveProductIs422() {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(products.findById("p1")).thenReturn(Map.of("id", "p1", "title", "Old",
                "price", 9.99, "active", false));
        Dtos.OrderCreateRequest req = new Dtos.OrderCreateRequest(1L,
                List.of(new Dtos.OrderItemCreate("p1", 1)), null);
        com.ecommerce.web.ApiException ex = assertThrows(
                com.ecommerce.web.ApiException.class, () -> service.placeOrder(req));
        assertEquals(422, ex.getStatus());
    }
}
