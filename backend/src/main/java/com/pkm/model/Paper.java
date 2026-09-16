package com.pkm.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "papers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Paper {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "external_id", unique = true, nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String source;          // arxiv | core | pmc

    @Column(nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> authors;

    @Column(name = "abstract", columnDefinition = "text")
    private String abstractText;

    @Column(name = "full_text_url")
    private String fullTextUrl;

    @Column(name = "published_at")
    private LocalDate publishedAt;

    @Column(name = "citation_count")
    private Integer citationCount;

    @Column(name = "year")
    private Integer year;

    @Column(name = "doi")
    private String doi;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }
}
