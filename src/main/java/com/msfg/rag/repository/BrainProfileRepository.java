package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BrainProfileRepository extends JpaRepository<BrainProfile, UUID> {
    Optional<BrainProfile> findByBrainId(UUID brainId);
}
