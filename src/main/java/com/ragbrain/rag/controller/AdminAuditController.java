package com.ragbrain.rag.controller;

import com.ragbrain.rag.domain.AuditLog;
import com.ragbrain.rag.dto.AuditLogDetailDto;
import com.ragbrain.rag.dto.AuditLogListDto;
import com.ragbrain.rag.dto.AuditPageDto;
import com.ragbrain.rag.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only audit trail for the dashboard. List rows are light; the detail
 * view adds the answer and retrieved sources. The final prompt is never
 * exposed over HTTP — it stays in the database for offline review.
 */
@RestController
@RequestMapping("/api/ai/admin/audit")
public class AdminAuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository repository;

    public AdminAuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public AuditPageDto list(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             @RequestParam(defaultValue = "false") boolean escalatedOnly,
                             @RequestParam(required = false) String q) {
        int clampedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        String query = q == null || q.isBlank() ? null : q.strip();
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), clampedSize);
        Page<AuditLog> result = query == null
                ? repository.search(escalatedOnly, pageRequest)
                : repository.search(escalatedOnly, query, pageRequest);
        return new AuditPageDto(
                result.getContent().stream().map(AuditLogListDto::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @GetMapping("/{id}")
    public AuditLogDetailDto detail(@PathVariable UUID id) {
        return repository.findById(id).map(AuditLogDetailDto::from)
                .orElseThrow(() -> new IllegalArgumentException("Audit entry not found: " + id));
    }
}
