package com.ragbrain.rag.repository;

import com.ragbrain.rag.domain.BrainDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BrainDocumentRepository extends JpaRepository<BrainDocument, UUID> {

    List<BrainDocument> findByActiveTrue();

    long countByActiveTrue();

    List<BrainDocument> findByBrainId(UUID brainId);

    List<BrainDocument> findByBrainIdAndActiveTrue(UUID brainId);

    long countByBrainId(UUID brainId);

    long countByBrainIdAndActiveTrue(UUID brainId);
}
