package com.msfg.rag.repository;

import com.msfg.rag.domain.Brain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainRepositoryTest {

    private static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainRepository brains;

    @Autowired
    private TestEntityManager em;

    @Test
    void migrationSeedsExactlyOneDefaultBrain() {
        Brain def = brains.findDefaultBrain().orElseThrow();
        assertEquals(DEFAULT_ID, def.getId());
        assertEquals("mortgage", def.getSlug());
        assertEquals("packs/msfg-mortgage", def.getPackRef());
        assertTrue(def.isDefault());
        assertTrue(def.isActive());
    }

    @Test
    void atMostOneDefaultBrainIsAllowed() {
        Brain second = new Brain(UUID.randomUUID(), "second", "Second Brain");
        second.setDefault(true);
        assertThrows(DataIntegrityViolationException.class, () -> brains.saveAndFlush(second));
    }

    @Test
    void existingTableInsertWithoutBrainIdDefaultsToDefaultBrain() {
        em.getEntityManager().createNativeQuery(
                "INSERT INTO brain_documents (title, source_name, source_type, file_name) " +
                "VALUES ('T', 'S', 'educational', 'f.pdf')").executeUpdate();
        em.flush();
        Object brainId = em.getEntityManager().createNativeQuery(
                "SELECT brain_id FROM brain_documents WHERE file_name = 'f.pdf'").getSingleResult();
        assertEquals(DEFAULT_ID, UUID.fromString(brainId.toString()));
    }
}
