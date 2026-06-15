package com.msfg.rag.repository;

import com.msfg.rag.domain.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuditLogRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    private static final UUID DEFAULT_BRAIN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    AuditLogRepository repository;

    private AuditLog log(String question, boolean escalated) {
        AuditLog entry = new AuditLog();
        entry.setUserQuestion(question);
        entry.setFallbackUsed(false);
        entry.setHumanEscalationRequired(escalated);
        entry.setBrainId(DEFAULT_BRAIN);
        return entry;
    }

    @Test
    void brainIdRoundTripsThroughPersistence() {
        AuditLog saved = repository.save(log("What is PMI?", false));
        AuditLog reloaded = repository.findById(saved.getId()).orElseThrow();
        assertEquals(DEFAULT_BRAIN, reloaded.getBrainId(),
                "brain_id must persist and reload on the audit record");
    }

    @Test
    void searchFiltersEscalationAndQuestionSubstring() {
        repository.save(log("What is PMI?", false));
        repository.save(log("Will I be approved?", true));
        repository.save(log("What is an FHA loan?", false));

        // no-q overload (null/blank q → two-param variant)
        assertEquals(3, repository.search(false, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repository.search(true, PageRequest.of(0, 10)).getTotalElements());
        // q overload
        assertEquals(2, repository.search(false, "what is", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repository.search(false, "fha", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, repository.search(true, "pmi", PageRequest.of(0, 10)).getTotalElements());
    }
}
