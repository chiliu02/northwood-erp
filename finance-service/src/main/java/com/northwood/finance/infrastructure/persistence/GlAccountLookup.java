package com.northwood.finance.infrastructure.persistence;

import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lightweight read port for {@code finance.gl_account}. Phase 5b's journal
 * postings reference 5 well-known accounts by code (1000 Bank, 1100 AR,
 * 2100 AP, 4000 Sales Revenue, 5000 COGS); this lookup resolves the account
 * id + name once per journal-line construction.
 */
@Repository
public class GlAccountLookup {

    private final JdbcTemplate jdbc;

    public GlAccountLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GlAccount byCode(String accountCode) {
        try {
            return jdbc.queryForObject(
                "SELECT gl_account_id, account_code, account_name FROM finance.gl_account WHERE account_code = ?",
                (rs, n) -> new GlAccount(
                    rs.getObject("gl_account_id", UUID.class),
                    rs.getString("account_code"),
                    rs.getString("account_name")
                ),
                accountCode
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                "GL account " + accountCode + " not seeded; check db/northwood_erp.sql"
            );
        }
    }

    public record GlAccount(UUID id, String code, String name) {}
}
