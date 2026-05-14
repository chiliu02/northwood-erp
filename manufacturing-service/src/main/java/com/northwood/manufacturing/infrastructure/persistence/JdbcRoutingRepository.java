package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.domain.Routing;
import com.northwood.manufacturing.domain.RoutingId;
import com.northwood.manufacturing.domain.RoutingOperation;
import com.northwood.manufacturing.domain.RoutingRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRoutingRepository implements RoutingRepository {

    private final JdbcTemplate jdbc;

    public JdbcRoutingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Routing> findActiveByFinishedProductId(UUID finishedProductId) {
        UUID routingHeaderId;
        try {
            routingHeaderId = jdbc.queryForObject(
                """
                SELECT routing_header_id FROM manufacturing.routing_header
                WHERE finished_product_id = ? AND status = 'active'
                """,
                UUID.class, finishedProductId
            );
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
        if (routingHeaderId == null) {
            return Optional.empty();
        }
        List<RoutingOperation> operations = jdbc.query(
            """
            SELECT routing_operation_id, operation_sequence, operation_code, description,
                   work_center_id, planned_setup_minutes, planned_run_minutes
            FROM manufacturing.routing_operation
            WHERE routing_header_id = ? ORDER BY operation_sequence
            """,
            (rs, n) -> new RoutingOperation(
                rs.getObject("routing_operation_id", UUID.class),
                rs.getInt("operation_sequence"),
                rs.getString("operation_code"),
                rs.getString("description"),
                rs.getObject("work_center_id", UUID.class),
                rs.getBigDecimal("planned_setup_minutes"),
                rs.getBigDecimal("planned_run_minutes")
            ),
            routingHeaderId
        );
        return Optional.of(new Routing(
            RoutingId.of(routingHeaderId),
            finishedProductId,
            "1",
            Routing.ACTIVE,
            operations
        ));
    }
}
