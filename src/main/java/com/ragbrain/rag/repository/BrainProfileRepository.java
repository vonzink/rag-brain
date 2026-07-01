package com.ragbrain.rag.repository;

import com.ragbrain.rag.domain.BrainProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BrainProfileRepository extends JpaRepository<BrainProfile, UUID> {
    Optional<BrainProfile> findByBrainId(UUID brainId);
}
