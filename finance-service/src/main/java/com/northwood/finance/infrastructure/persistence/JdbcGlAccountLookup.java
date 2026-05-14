package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.application.GlAccountLookup;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGlAccountLookup implements GlAccountLookup {

    private final JdbcTemplate jdbc;

    public JdbcGlAccountLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
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
}
