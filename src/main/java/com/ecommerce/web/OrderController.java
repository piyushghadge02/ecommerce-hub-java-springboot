package com.ecommerce.web;

import com.ecommerce.dto.Dtos;
import com.ecommerce.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<Dtos.OrderCreateResponse> create(
            @Valid @RequestBody Dtos.OrderCreateRequest payload) {
        OrderService.PlacedOrder placed = orders.placeOrder(payload);
        return ResponseEntity.status(placed.statusCode()).body(placed.body());
    }

    @GetMapping("/{orderId}")
    public Dtos.OrderDetailResponse get(@PathVariable long orderId) {
        return orders.getOrderDetail(orderId);
    }

    @PatchMapping("/{orderId}/status")
    public Dtos.StatusUpdateResponse updateStatus(@PathVariable long orderId,
                                                  @RequestBody Dtos.StatusUpdateRequest payload) {
        if (payload == null || payload.status() == null)
            throw ApiException.unprocessable("status is required");
        return orders.updateOrderStatus(orderId, payload.status());
    }
}
