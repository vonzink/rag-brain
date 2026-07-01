package com.ragbrain.rag.repository;

import com.ragbrain.rag.domain.ClarificationRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClarificationRuleRepository extends JpaRepository<ClarificationRule, UUID> {
    List<ClarificationRule> findByBrainIdAndActiveTrueOrderByPriorityAsc(UUID brainId);
}
