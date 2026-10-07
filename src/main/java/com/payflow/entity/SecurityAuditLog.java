package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only security event (FS-10): forged/replayed webhooks, invalid API keys. */
@Entity
@Immutable
@Table(name = "security_audit_log")
public class SecurityAuditLog {

    public static final String WEBHOOK_SIGNATURE_INVALID = "WEBHOOK_SIGNATURE_INVALID";
    public static final String WEBHOOK_TIMESTAMP_OUT_OF_TOLERANCE = "WEBHOOK_TIMESTAMP_OUT_OF_TOLERANCE";
    public static final String WEBHOOK_VERIFICATION_FAILED = "WEBHOOK_VERIFICATION_FAILED";
    public static final String API_KEY_INVALID = "API_KEY_INVALID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(length = 32)
    private String gateway;

    @Column(name = "source_ip", nullable = false, length = 64)
    private String sourceIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "request_path", nullable = false)
    private String requestPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SecurityAuditLog() {}

    public SecurityAuditLog(String eventType, String gateway, String sourceIp, String userAgent,
                            String requestPath, Map<String, Object> detail) {
        this.eventType = eventType;
        this.gateway = gateway;
        this.sourceIp = sourceIp;
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length()));
        this.requestPath = requestPath;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public String getGateway() { return gateway; }
    public String getSourceIp() { return sourceIp; }
    public String getUserAgent() { return userAgent; }
    public String getRequestPath() { return requestPath; }
    public Map<String, Object> getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
