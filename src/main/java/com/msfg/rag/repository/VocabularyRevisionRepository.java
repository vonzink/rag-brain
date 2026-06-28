package com.msfg.rag.repository;

import com.msfg.rag.domain.VocabularyRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VocabularyRevisionRepository extends JpaRepository<VocabularyRevision, UUID> {

    Optional<VocabularyRevision> findFirstByBrainIdOrderByCreatedAtDescIdDesc(UUID brainId);

    List<VocabularyRevision> findTop20ByBrainIdOrderByCreatedAtDescIdDesc(UUID brainId);
}
