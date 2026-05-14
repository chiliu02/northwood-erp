package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.dto.FinancialDashboardSnapshot;
import com.northwood.reporting.application.dto.FinancialDashboardView;
import com.northwood.reporting.application.FinancialDashboardQueryPort;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFinancialDashboardQueryPort implements FinancialDashboardQueryPort {

    private static final String SELECT_BASE = """
        SELECT dashboard_date, currency_code,
               sales_revenue, cost_of_goods_sold, gross_profit,
               inventory_value, wip_value,
               accounts_receivable, accounts_payable,
               cash_received, cash_paid,
               open_sales_orders_count, open_purchase_orders_count, open_work_orders_count,
               updated_at
          FROM reporting.financial_dashboard_daily
        """;

    private static final RowMapper<FinancialDashboardView> MAPPER = (rs, n) -> {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new FinancialDashboardView(
            rs.getDate("dashboard_date").toLocalDate(),
            rs.getString("currency_code"),
            rs.getBigDecimal("sales_revenue"),
            rs.getBigDecimal("cost_of_goods_sold"),
            rs.getBigDecimal("gross_profit"),
            rs.getBigDecimal("inventory_value"),
            rs.getBigDecimal("wip_value"),
            rs.getBigDecimal("accounts_receivable"),
            rs.getBigDecimal("accounts_payable"),
            rs.getBigDecimal("cash_received"),
            rs.getBigDecimal("cash_paid"),
            rs.getInt("open_sales_orders_count"),
            rs.getInt("open_purchase_orders_count"),
            rs.getInt("open_work_orders_count"),
            updatedAt == null ? null : updatedAt.toInstant()
        );
    };

    private final JdbcTemplate jdbc;

    public JdbcFinancialDashboardQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FinancialDashboardView> findByCurrency(String currencyCode) {
        return jdbc.query(
            SELECT_BASE + " WHERE currency_code = ? ORDER BY dashboard_date DESC",
            MAPPER, currencyCode);
    }

    @Override
    public Optional<FinancialDashboardView> findByDate(LocalDate date, String currencyCode) {
        List<FinancialDashboardView> rows = jdbc.query(
            SELECT_BASE + " WHERE dashboard_date = ? AND currency_code = ?",
            MAPPER, Date.valueOf(date), currencyCode);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public FinancialDashboardSnapshot findSnapshot(String currencyCode) {
        // AR = amounts billed to customers but not yet collected — only
        // invoiced lines count (placed-but-not-yet-invoiced sits in backlog,
        // not AR). Open SO = any non-cancelled order still owing the
        // customer (outstanding > 0); covers placed-not-yet-fully-paid.
        BigDecimal[] arOpenSo = jdbc.query("""
            SELECT
                COALESCE(SUM(GREATEST(invoiced_amount - paid_amount, 0)), 0) AS ar,
                COUNT(*) FILTER (WHERE outstanding_amount > 0 AND order_status <> 'cancelled') AS open_count
              FROM reporting.sales_order_360_view
             WHERE currency_code = ?
            """,
            (rs, n) -> new BigDecimal[] { rs.getBigDecimal("ar"), BigDecimal.valueOf(rs.getInt("open_count")) },
            currencyCode
        ).get(0);

        BigDecimal[] apOpenPo = jdbc.query("""
            SELECT
                COALESCE(SUM(GREATEST(invoiced_amount - paid_amount, 0)), 0) AS ap,
                COUNT(*) FILTER (WHERE po_status NOT IN ('paid', 'closed', 'cancelled')) AS open_count
              FROM reporting.purchase_order_tracking_view
             WHERE currency_code = ?
            """,
            (rs, n) -> new BigDecimal[] { rs.getBigDecimal("ap"), BigDecimal.valueOf(rs.getInt("open_count")) },
            currencyCode
        ).get(0);

        // Work orders don't carry a currency_code on the projection — they're
        // physical, not financial. The currency filter is for AR/AP only.
        Integer openWoCount = jdbc.queryForObject("""
            SELECT COUNT(*) AS open_count
              FROM reporting.production_planning_board
             WHERE work_order_status NOT IN ('completed', 'cancelled')
            """,
            Integer.class
        );

        // inventory_value = SUM(on_hand_quantity * standard_cost). Filter by
        // currency_code on the cost cache; ATP carries no currency (it's a
        // physical quantity). Products missing from the cost cache are
        // excluded (LEFT JOIN with WHERE filter would over-count zeros; an
        // INNER JOIN naturally drops them).
        BigDecimal inventoryValue = jdbc.queryForObject("""
            SELECT COALESCE(SUM(atp.on_hand_quantity * psc.standard_cost), 0) AS inventory_value
              FROM reporting.available_to_promise_view atp
              JOIN reporting.product_standard_cost psc
                ON atp.product_id = psc.product_id
             WHERE psc.currency_code = ?
            """,
            BigDecimal.class, currencyCode
        );

        return new FinancialDashboardSnapshot(
            currencyCode,
            arOpenSo[0],
            apOpenPo[0],
            inventoryValue == null ? BigDecimal.ZERO : inventoryValue,
            arOpenSo[1].intValue(),
            apOpenPo[1].intValue(),
            openWoCount == null ? 0 : openWoCount,
            Instant.now()
        );
    }

}
