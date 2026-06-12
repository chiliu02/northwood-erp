package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.application.WorkCenterRateLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkCenterRateLookup implements WorkCenterRateLookup {

    private final JdbcTemplate jdbc;

    public JdbcWorkCenterRateLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Rates> findByWorkCenterId(UUID workCenterId) {
        try {
            Rates rates = jdbc.queryForObject(
                """
                SELECT labour_rate_per_minute, overhead_rate_per_minute
                FROM manufacturing.work_center
                WHERE work_center_id = ?
                """,
                (rs, n) -> new Rates(
                    rs.getBigDecimal("labour_rate_per_minute"),
                    rs.getBigDecimal("overhead_rate_per_minute")
                ),
                workCenterId
            );
            return Optional.ofNullable(rates);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
