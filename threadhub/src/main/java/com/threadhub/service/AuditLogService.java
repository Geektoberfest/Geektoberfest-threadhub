package com.threadhub.service;

import com.threadhub.event.AuditEvent;
import com.threadhub.model.AuditAction;
import com.threadhub.model.AuditLog;
import com.threadhub.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Async
    @EventListener
    @Transactional
    public void handleAuditEvent(AuditEvent event) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .userId(event.getUserId())
                    .action(event.getAction())
                    .resourceType(event.getResourceType())
                    .resourceId(event.getResourceId())
                    .metadata(event.getMetadata())
                    .build();

            auditLogRepository.save(auditLog);
            log.info("Audit log recorded: action={}, userId={}", event.getAction(), event.getUserId());
        } catch (Exception e) {
            log.error("Failed to asynchronously persist audit log", e);
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogs(AuditAction action, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        if (action != null && from != null && to != null) {
            return auditLogRepository.findByActionAndTimestampBetween(action, from, to, pageable);
        } else if (action != null) {
            return auditLogRepository.findByAction(action, pageable);
        } else if (from != null && to != null) {
            return auditLogRepository.findByTimestampBetween(from, to, pageable);
        }
        return auditLogRepository.findAll(pageable);
    }
}