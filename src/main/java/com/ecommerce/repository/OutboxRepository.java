package com.ecommerce.repository;

import com.ecommerce.model.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    /** Due pending rows. */
    @Query(value = "select * from outbox where status = 'pending' and next_attempt_at <= :now " +
            "order by id asc limit :limit", nativeQuery = true)
    List<Outbox> claimDueBatch(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Query("select o.status as status, count(o) as cnt from Outbox o group by o.status")
    List<Object[]> countByStatus();

    default java.util.Map<String, Long> countByStatusMap() {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        for (Object[] row : countByStatus()) {
            m.put((String) row[0], (Long) row[1]);
        }
        return m;
    }
}
