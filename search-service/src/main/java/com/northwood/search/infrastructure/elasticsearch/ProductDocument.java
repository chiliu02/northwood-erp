package com.northwood.search.infrastructure.elasticsearch;

import java.math.BigDecimal;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;

/**
 * The {@code products} Elasticsearch read model (read model A). The document id
 * is the product's aggregate id, so projector upserts are idempotent.
 *
 * <p>{@code sku} and {@code name} are multi-fields: a {@code keyword} main field
 * for exact match / facet / sort, plus a tokenized {@code text} sub-field for
 * full-text + fuzzy match. {@code name.text} also backs prefix autocomplete via
 * {@code match_phrase_prefix}. Mapped from these annotations at startup by
 * {@code ElasticsearchIndexInitializer}; documents are written as partial
 * upserts (see {@code ElasticsearchProductCatalogProjection}) and read back into
 * this record by {@code ElasticsearchProductSearchQueryPort}.
 */
@Document(indexName = ProductDocument.INDEX)
public record ProductDocument(
    @Id String productId,
    @MultiField(
        mainField = @Field(type = FieldType.Keyword),
        otherFields = @InnerField(suffix = "text", type = FieldType.Text)
    ) String sku,
    @MultiField(
        mainField = @Field(type = FieldType.Text),
        otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword)
    ) String name,
    @Field(type = FieldType.Keyword) String productType,
    @Field(type = FieldType.Keyword) String status,
    @Field(type = FieldType.Double) BigDecimal salesPrice,
    @Field(type = FieldType.Keyword) String currencyCode
) {

    public static final String INDEX = "products";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISCONTINUED = "discontinued";
}
