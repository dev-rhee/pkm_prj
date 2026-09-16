package com.pkm.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "saved_papers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SavedPaper {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    @Column(name = "notion_page_id")
    private String notionPageId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "linked_notion_ids", columnDefinition = "text[]")
    private List<String> linkedNotionIds;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }
}
