package com.payflow.security;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.entity.SecurityAuditLog;
import com.payflow.repository.SecurityAuditLogRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Security audit trail (FS-10): every rejected webhook or API credential is
 * recorded with the source IP.
 *
 * <p>The structured log line is always written first; the database row is
 * written in its own transaction and never allowed to fail the request, so a
 * struggling database cannot turn security logging into a recursive failure
 * (lesson C5.3).</p>
 */
@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger("SECURITY");

    private final SecurityAuditLogRepository repo;
    private final TransactionTemplate tx;

    public SecurityAuditService(SecurityAuditLogRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String eventType, String gateway, String sourceIp, String userAgent, String requestPath,
                       Map<String, Object> detail) {
        log.warn("security event {} {} {} {} {}", kv("component", "security_audit"), kv("event_type", eventType),
                kv("gateway", gateway), kv("source_ip", sourceIp), kv("detail", detail));
        try {
            tx.executeWithoutResult(s -> repo.save(new SecurityAuditLog(eventType, gateway,
                    sourceIp == null ? "unknown" : sourceIp, userAgent, requestPath, detail)));
        } catch (RuntimeException e) {
            log.error("security audit row not persisted (logged above) {}", kv("error", e.getMessage()));
        }
    }

    public List<SecurityAuditLog> recent() {
        return repo.findTop100ByOrderByCreatedAtDesc();
    }
}
