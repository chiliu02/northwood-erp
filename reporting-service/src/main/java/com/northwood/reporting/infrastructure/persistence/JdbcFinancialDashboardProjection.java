package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcFinancialDashboardProjection implements FinancialDashboardProjection {

    private static final String DEFAULT_CURRENCY = Currencies.AUD;

    private final JdbcTemplate jdbc;

    public JdbcFinancialDashboardProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void recordCustomerInvoice(BigDecimal amount, String currencyCode, Instant occurredAt) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        if (v.signum() <= 0) return;
        upsertMoney("sales_revenue", v, currencyCode, occurredAt, true);
    }

    @Override
    @Transactional
    public void recordSupplierInvoice(BigDecimal amount, String currencyCode, Instant occurredAt) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        if (v.signum() <= 0) return;
        upsertMoney("cost_of_goods_sold", v, currencyCode, occurredAt, true);
    }

    @Override
    @Transactional
    public void recordCustomerPayment(BigDecimal amount, String currencyCode, Instant occurredAt) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        if (v.signum() <= 0) return;
        upsertMoney("cash_received", v, currencyCode, occurredAt, false);
    }

    @Override
    @Transactional
    public void recordSupplierPayment(BigDecimal amount, String currencyCode, Instant occurredAt) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        if (v.signum() <= 0) return;
        upsertMoney("cash_paid", v, currencyCode, occurredAt, false);
    }

    @Override
    @Transactional
    public void refreshDailyBalances(LocalDate date, String currencyCode) {
        String currency = currencyCode == null ? DEFAULT_CURRENCY : currencyCode;
        // Single round-trip: four scalar sub-selects feed an UPSERT. AR / AP /
        // inventory_value mirror JdbcFinancialDashboardQueryPort.findSnapshot's
        // SQL exactly — that path stays as the as-of-now realtime view; this
        // one writes the same values into the per-day row so historical
        // queries work.
        jdbc.update("""
            INSERT INTO reporting.financial_dashboard_daily (
                dashboard_date, currency_code,
                sales_revenue, cost_of_goods_sold, gross_profit,
                inventory_value, wip_value,
                accounts_receivable, accounts_payable,
                cash_received, cash_paid,
                open_sales_orders_count, open_purchase_orders_count, open_work_orders_count,
                updated_at
            ) VALUES (
                ?, ?,
                0, 0, 0,
                (SELECT COALESCE(SUM(atp.on_hand_quantity * psc.standard_cost), 0)
                   FROM reporting.available_to_promise_view atp
                   JOIN reporting.product_card psc ON atp.product_id = psc.product_id
                  WHERE psc.currency_code = ?),
                0,
                (SELECT COALESCE(SUM(GREATEST(invoiced_amount - paid_amount, 0)), 0)
                   FROM reporting.sales_order_360_view
                  WHERE currency_code = ?),
                (SELECT COALESCE(SUM(GREATEST(invoiced_amount - paid_amount, 0)), 0)
                   FROM reporting.purchase_order_tracking_view
                  WHERE currency_code = ?),
                0, 0,
                (SELECT COUNT(*)
                   FROM reporting.sales_order_360_view
                  WHERE currency_code = ?
                    AND outstanding_amount > 0
                    AND order_status <> 'cancelled'),
                (SELECT COUNT(*)
                   FROM reporting.purchase_order_tracking_view
                  WHERE currency_code = ?
                    AND po_status NOT IN ('paid', 'closed', 'cancelled')),
                (SELECT COUNT(*)
                   FROM reporting.production_planning_board
                  WHERE work_order_status NOT IN ('completed', 'cancelled')),
                now()
            )
            ON CONFLICT (dashboard_date, currency_code) DO UPDATE SET
                inventory_value = EXCLUDED.inventory_value,
                accounts_receivable = EXCLUDED.accounts_receivable,
                accounts_payable = EXCLUDED.accounts_payable,
                open_sales_orders_count = EXCLUDED.open_sales_orders_count,
                open_purchase_orders_count = EXCLUDED.open_purchase_orders_count,
                open_work_orders_count = EXCLUDED.open_work_orders_count,
                updated_at = now()
            """,
            Date.valueOf(date), currency,
            currency,    // inventory_value filter
            currency,    // AR filter
            currency,    // AP filter
            currency,    // open SO filter
            currency     // open PO filter
        );
    }

    private void upsertMoney(String column, BigDecimal delta, String currencyCode,
                              Instant occurredAt, boolean recomputeGross) {
        LocalDate date = (occurredAt == null ? Instant.now() : occurredAt)
            .atZone(ZoneId.systemDefault()).toLocalDate();
        String currency = currencyCode == null ? DEFAULT_CURRENCY : currencyCode;
        String grossUpdate = recomputeGross
            ? "gross_profit = (financial_dashboard_daily.sales_revenue + CASE WHEN 'COL' = 'sales_revenue' THEN ? ELSE 0 END)"
              + " - (financial_dashboard_daily.cost_of_goods_sold + CASE WHEN 'COL' = 'cost_of_goods_sold' THEN ? ELSE 0 END),"
            : "";
        String sql = ("""
            INSERT INTO reporting.financial_dashboard_daily (
                dashboard_date, currency_code,
                sales_revenue, cost_of_goods_sold, gross_profit,
                inventory_value, wip_value,
                accounts_receivable, accounts_payable,
                cash_received, cash_paid,
                open_sales_orders_count, open_purchase_orders_count, open_work_orders_count,
                updated_at
            ) VALUES (?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, now())
            ON CONFLICT (dashboard_date, currency_code) DO UPDATE SET
                COL = financial_dashboard_daily.COL + ?,
                """ + grossUpdate + """
                updated_at = now()
            """).replace("COL", column);
        if (recomputeGross) {
            jdbc.update(sql,
                Date.valueOf(date), currency,
                delta, delta, delta);
        } else {
            jdbc.update(sql,
                Date.valueOf(date), currency,
                delta);
        }
    }
}
