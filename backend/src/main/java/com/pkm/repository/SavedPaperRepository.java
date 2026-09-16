package com.pkm.repository;

import com.pkm.model.SavedPaper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SavedPaperRepository extends JpaRepository<SavedPaper, UUID> {
    List<SavedPaper> findByPaperId(UUID paperId);
}
