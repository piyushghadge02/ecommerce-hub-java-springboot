package com.ecommerce.repository;

import com.ecommerce.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    List<Order> findByUpdatedAtGreaterThanOrderByUpdatedAtAscIdAsc(OffsetDateTime updatedAt);

    @Query("select o from Order o where o.updatedAt > :after order by o.updatedAt asc, o.id asc")
    List<Order> findUpdatedAfter(OffsetDateTime after, org.springframework.data.domain.Pageable pageable);
}
