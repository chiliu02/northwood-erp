package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.dto.SagaRowView;
import com.northwood.purchasing.application.saga.SagaConsoleQueryPort;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcSagaConsoleQueryPort implements SagaConsoleQueryPort {

    private static final String SQL_LIST = """
        SELECT saga_id, purchase_order_header_id, saga_state, current_step,
               last_error, retry_count, version,
               created_at, updated_at, completed_at
          FROM purchasing.purchase_to_pay_saga
         ORDER BY updated_at DESC
        """;

    private final JdbcTemplate jdbc;

    public JdbcSagaConsoleQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SagaRowView> listSagas() {
        return jdbc.query(SQL_LIST, MAPPER);
    }

    private static final RowMapper<SagaRowView> MAPPER = (rs, n) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new SagaRowView(
            rs.getObject("saga_id", UUID.class),
            rs.getObject("purchase_order_header_id", UUID.class),
            "purchase_order_header_id",
            "purchase_to_pay",
            rs.getString("saga_state"),
            rs.getString("current_step"),
            rs.getString("last_error"),
            rs.getInt("retry_count"),
            rs.getLong("version"),
            createdAt   == null ? null : createdAt.toInstant(),
            updatedAt   == null ? null : updatedAt.toInstant(),
            completedAt == null ? null : completedAt.toInstant()
        );
    };
}
