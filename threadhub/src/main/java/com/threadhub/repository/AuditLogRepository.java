package com.threadhub.repository;

import com.threadhub.model.AuditAction;
import com.threadhub.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByAction(AuditAction action, Pageable pageable);

    Page<AuditLog> findByTimestampBetween(LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<AuditLog> findByActionAndTimestampBetween(AuditAction action, LocalDateTime from, LocalDateTime to, Pageable pageable);
}