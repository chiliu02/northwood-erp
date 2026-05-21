package com.northwood.shared.infrastructure.saga;

import com.northwood.shared.domain.Assert;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * On {@link ApplicationReadyEvent}, validates every registered
 * {@link SagaStateInvariantCheck} against its schema's CHECK constraint —
 * fails the boot if any state name in code is missing from the DB list.
 *
 * <p>Catches the drift mode we hit on 2026-05-05 (the {@code invoice_paid}
 * saga state was added to code without updating the baseline's CHECK; mocked unit
 * tests passed because they never reached an INSERT; a real partial customer
 * payment would have crashed). Now every service's first boot after a code
 * change either succeeds end-to-end or fails fast with a clear "code state X
 * not in DB CHECK list" message.
 *
 * <p>Implementation: queries {@code pg_get_constraintdef} for each
 * (schema, table) pair, regex-extracts the single-quoted state literals
 * from the resulting {@code CHECK (saga_state IN (...))} text, and asserts
 * {@code codeStates ⊆ dbStates}. If a check is missing entirely or the
 * regex finds no literals, the boot fails with a guidance message rather
 * than silently passing.
 */
public class SagaStateInvariantChecker {

    private static final Logger log = LoggerFactory.getLogger(SagaStateInvariantChecker.class);

    /** Matches single-quoted literals inside a `CHECK (col IN ('a', 'b', ...))` definition. */
    private static final Pattern STATE_LITERAL = Pattern.compile("'([^']+)'");

    private final JdbcTemplate jdbc;
    private final List<SagaStateInvariantCheck> checks;

    public SagaStateInvariantChecker(JdbcTemplate jdbc, List<SagaStateInvariantCheck> checks) {
        this.jdbc = jdbc;
        this.checks = checks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        if (checks.isEmpty()) {
            return;
        }
        for (SagaStateInvariantCheck check : checks) {
            verifyOne(check);
        }
        log.info("saga-state invariants verified for {} saga(s)", checks.size());
    }

    private void verifyOne(SagaStateInvariantCheck check) {
        Set<String> dbStates = readCheckLiterals(check);
        Set<String> missing = new LinkedHashSet<>(check.codeStates());
        missing.removeAll(dbStates);
        Assert.state(missing.isEmpty(), String.format(
                "Saga state CHECK on %s.%s.%s is missing %d code-side state(s): %s. "
                    + "Code can write these but the DB CHECK will reject them at INSERT/UPDATE. "
                    + "Add them via a Liquibase changeset (drop + re-add the CHECK).",
                check.schemaName(), check.tableName(), check.columnName(),
                missing.size(), missing
            ));
        log.debug("saga-state invariant ok: {}.{}.{} (code states ⊆ db states; db has {} state(s))",
            check.schemaName(), check.tableName(), check.columnName(), dbStates.size());
    }

    private Set<String> readCheckLiterals(SagaStateInvariantCheck check) {
        // Find every CHECK constraint on the table; pick the one whose
        // definition mentions the column. There is typically exactly one.
        List<String> defs;
        try {
            defs = jdbc.queryForList(
                """
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                JOIN pg_namespace n ON t.relnamespace = n.oid
                WHERE n.nspname = ?
                  AND t.relname = ?
                  AND c.contype = 'c'
                """,
                String.class,
                check.schemaName(), check.tableName()
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                "No CHECK constraints found on " + check.schemaName() + "." + check.tableName()
                    + " — cannot verify saga state invariant.", e
            );
        }

        Set<String> states = new LinkedHashSet<>();
        boolean foundColumnCheck = false;
        for (String def : defs) {
            if (def == null) continue;
            // Pick CHECKs that mention the column. There can be other CHECKs
            // (e.g. retry_count >= 0) we don't care about.
            if (!def.contains(check.columnName())) {
                continue;
            }
            foundColumnCheck = true;
            Matcher m = STATE_LITERAL.matcher(def);
            while (m.find()) {
                states.add(m.group(1));
            }
        }
        Assert.state(foundColumnCheck, "No CHECK constraint on " + check.schemaName() + "." + check.tableName()
                    + "." + check.columnName() + " — cannot verify state list.");
        Assert.stateNotEmpty(states, "CHECK constraint on " + check.schemaName() + "." + check.tableName()
                    + "." + check.columnName() + " yielded no string literals — "
                    + "the regex used to extract state names didn't match. "
                    + "Check the constraint definition shape.");
        return Collections.unmodifiableSet(states);
    }
}
