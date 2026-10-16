package com.ecommerce.web;

import com.ecommerce.dto.Dtos;
import com.ecommerce.repository.OutboxRepository;
import com.ecommerce.repository.SyncStateRepository;
import com.ecommerce.service.SyncService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final OutboxRepository outbox;
    private final SyncStateRepository syncState;
    private final SyncService sync;

    public AdminController(OutboxRepository outbox, SyncStateRepository syncState,
                           SyncService sync) {
        this.outbox = outbox;
        this.syncState = syncState;
        this.sync = sync;
    }

    @GetMapping("/sync-status")
    @Transactional(readOnly = true)
    public Dtos.SyncStatusResponse syncStatus() {
        Map<String, Long> counts = outbox.countByStatusMap();
        long pending = counts.getOrDefault("pending", 0L) + counts.getOrDefault("processing", 0L);
        long failed = counts.getOrDefault("failed", 0L);
        String watermark = syncState.findById(SyncService.POLLING_WATERMARK_KEY)
                .map(s -> s.getValue()).orElse(null);
        Long docs = sync.searchCountDocs();
        return new Dtos.SyncStatusResponse(pending, failed, watermark, docs);
    }

    @PostMapping("/reindex")
    public Dtos.ReindexResponse reindex() {
        try {
            long n = sync.reindexAll();
            return new Dtos.ReindexResponse(n);
        } catch (Exception e) {
            throw ApiException.unavailable("reindex unavailable: elasticsearch unreachable");
        }
    }
}
