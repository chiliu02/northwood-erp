package com.northwood.search.infrastructure.elasticsearch;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;

/**
 * The {@code entities} Elasticsearch read model (read model B) — one index with
 * a {@code type} discriminator holding every searchable entity across the
 * bounded contexts (product, customer, sales order, supplier, purchase order,
 * customer invoice), so a single query / one search box ranks them together.
 *
 * <p>The document id is {@code <entityType>:<aggregateId>} so the same UUID under
 * two entity types can't collide, and each projector upsert stays idempotent.
 * {@code title}/{@code code} are multi-fields (tokenized {@code text} for fuzzy +
 * a {@code keyword} for exact/sort/facet).
 */
@Document(indexName = EntityDocument.INDEX)
public record EntityDocument(
    @Id String id,
    @Field(type = FieldType.Keyword) String entityType,
    @Field(type = FieldType.Keyword) String entityId,
    @MultiField(
        mainField = @Field(type = FieldType.Text),
        otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword)
    ) String title,
    @MultiField(
        mainField = @Field(type = FieldType.Keyword),
        otherFields = @InnerField(suffix = "text", type = FieldType.Text)
    ) String code,
    @Field(type = FieldType.Text) String subtitle,
    @Field(type = FieldType.Keyword) String status
) {

    public static final String INDEX = "entities";
}
