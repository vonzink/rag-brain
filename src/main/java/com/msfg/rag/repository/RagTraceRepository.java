package com.msfg.rag.repository;

import com.msfg.rag.domain.RagTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RagTraceRepository extends JpaRepository<RagTrace, UUID> {
}
