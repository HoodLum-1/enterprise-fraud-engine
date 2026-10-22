package com.capitec.connect.fraudengine.infrastructure.lock;

import com.capitec.connect.fraudengine.engine.service.AccountLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op lock used where advisory locks are unavailable (the H2-backed unit tests).
 *
 * <p>PostgreSQL advisory locks have no H2 equivalent, and the unit tests run against H2 with
 * {@code ddl-auto: create-drop}. The lock is therefore disabled for those tests through
 * {@code fraud.engine.lock.enabled=false} rather than being stubbed at every call site.
 */
@Component
@ConditionalOnProperty(prefix = "fraud.engine.lock", name = "enabled", havingValue = "false")
public class NoOpAccountLock implements AccountLock {

    @Override
    public void lockForEvaluation(String accountId) {
        // Advisory locks are PostgreSQL-only; see PostgresAdvisoryAccountLock.
    }
}
