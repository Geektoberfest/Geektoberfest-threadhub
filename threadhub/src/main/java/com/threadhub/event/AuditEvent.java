package com.threadhub.event;

import com.threadhub.model.AuditAction;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class AuditEvent extends ApplicationEvent {

    private final Long userId;
    private final AuditAction action;
    private final String resourceType;
    private final Long resourceId;
    private final String metadata;

    public AuditEvent(Object source, Long userId, AuditAction action, String resourceType, Long resourceId, String metadata) {
        super(source);
        this.userId = userId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.metadata = metadata;
    }
}