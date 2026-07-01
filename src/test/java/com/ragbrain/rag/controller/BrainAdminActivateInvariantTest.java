package com.ragbrain.rag.controller;

import com.ragbrain.rag.TestBrains;
import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.pack.DomainPackRegistry;
import com.ragbrain.rag.pack.PackTemplateService;
import com.ragbrain.rag.repository.BrainRepository;
import com.ragbrain.rag.service.ai.ModelRouterService;
import com.ragbrain.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainAdminActivateInvariantTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainRepository brains;

    @Test
    void activateFlipsTheDefaultWithoutViolatingTheUniqueIndex() {
        BrainAdminController controller = new BrainAdminController(
                brains, mock(SyncService.class), mock(DomainPackRegistry.class), mock(ModelRouterService.class),
                mock(PackTemplateService.class));

        UUID secondId = UUID.randomUUID();
        Brain second = new Brain(secondId, "lending", "Lending Brain");
        second.setActive(true);
        second.setDefault(false);
        brains.saveAndFlush(second);

        controller.activate(secondId);

        assertEquals(secondId, brains.findDefaultBrain().orElseThrow().getId());
        assertTrue(brains.findById(TestBrains.DEFAULT_ID).orElseThrow().isActive());
        assertTrue(!brains.findById(TestBrains.DEFAULT_ID).orElseThrow().isDefault());
    }
}
