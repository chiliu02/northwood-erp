package com.northwood.graphqlbff.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.graphqlbff.dto.ProductView;
import com.northwood.graphqlbff.dto.StockItemView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Locks the batch-loader association behaviour the {@code @BatchMapping}
 * resolvers rely on: each source is paired with its keyed value in one pass,
 * and a source with no matching value is simply absent from the result map —
 * which Spring GraphQL renders as {@code null} for that field rather than an
 * error. (The end-to-end "one upstream call per type" proof via GraphQlTester
 * is a follow-up; it needs the Boot 4 GraphQL test-slice artifact.)
 */
class BatchLoadersTest {

    @Test
    void associatePairsEachSourceWithItsValueAndOmitsMisses() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();

        List<ProductView> products = List.of(product(a), product(b), product(orphan));
        List<StockItemView> stock = List.of(stockFor(a), stockFor(b)); // none for the orphan

        Map<UUID, StockItemView> byProduct = BatchLoaders.index(stock, StockItemView::productId);
        Map<ProductView, StockItemView> result = BatchLoaders.associate(products, ProductView::id, byProduct);

        assertThat(result).hasSize(2);
        assertThat(result.get(product(a)).productId()).isEqualTo(a);
        assertThat(result.get(product(b)).productId()).isEqualTo(b);
        assertThat(result).doesNotContainKey(product(orphan)); // missing -> null field, not an error
    }

    @Test
    void indexKeysValuesByTheExtractedId() {
        UUID a = UUID.randomUUID();
        Map<UUID, StockItemView> byProduct = BatchLoaders.index(List.of(stockFor(a)), StockItemView::productId);

        assertThat(byProduct).containsOnlyKeys(a);
        assertThat(byProduct.get(a).onHand()).isEqualByComparingTo("7");
    }

    private static ProductView product(UUID id) {
        return new ProductView(id, "SKU-" + id, "name", "desc", "finished_good",
            BigDecimal.TEN, BigDecimal.ONE, false, true,
            null, null, "make_to_stock", null, null, 0, "active", 0L);
    }

    private static StockItemView stockFor(UUID productId) {
        return new StockItemView(productId, "SKU-" + productId, "name", "finished_good",
            "EA", "tracked", BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("7"), BigDecimal.ZERO, new BigDecimal("7"));
    }
}
