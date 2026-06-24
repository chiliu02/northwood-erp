package com.northwood.search.api;

import com.northwood.search.application.ProductSearchQueryPort;
import com.northwood.search.application.dto.ProductSearchCriteria;
import com.northwood.search.application.dto.ProductSearchResults;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search read API over the Elasticsearch read models. Reached through the BFF
 * proxy at {@code /api/search/**} like every other service endpoint.
 *
 * <p>Read model A (product catalog): full-text fuzzy search with facets, plus
 * prefix autocomplete. The controller depends only on the application-layer
 * {@link ProductSearchQueryPort}; the Elasticsearch detail stays in
 * {@code infrastructure/}.
 */
@RestController
@RequestMapping("/api/search/products")
public class SearchController {

    private final ProductSearchQueryPort productSearch;

    public SearchController(ProductSearchQueryPort productSearch) {
        this.productSearch = productSearch;
    }

    @GetMapping
    public ProductSearchResults searchProducts(
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "type", required = false) String productType,
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "minPrice", required = false) BigDecimal minPrice,
        @RequestParam(name = "maxPrice", required = false) BigDecimal maxPrice,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return productSearch.search(
            new ProductSearchCriteria(q, productType, status, minPrice, maxPrice, page, size));
    }

    @GetMapping("/suggest")
    public List<String> suggestProducts(
        @RequestParam(name = "q") String q,
        @RequestParam(name = "limit", defaultValue = "10") int limit
    ) {
        return productSearch.suggest(q, limit);
    }
}
