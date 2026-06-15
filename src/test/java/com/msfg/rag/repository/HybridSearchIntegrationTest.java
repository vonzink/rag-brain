package com.msfg.rag.repository;

import com.msfg.rag.domain.DocumentChunk;
import com.msfg.rag.domain.MortgageDocument;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.service.ingestion.EmbeddingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the two halves of hybrid search against a real
 * pgvector database (Testcontainers). Embeddings are synthetic unit
 * vectors so no AI provider is needed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class HybridSearchIntegrationTest {

    private static final UUID DEFAULT_BRAIN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private MortgageDocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    @Autowired
    private EntityManager entityManager;

    private MortgageDocument activeDoc;

    @BeforeEach
    void setUp() {
        chunkRepository.deleteAll();
        documentRepository.deleteAll();

        activeDoc = saveDocument("Fannie Mae Selling Guide", true, null);

        saveChunk(activeDoc, 0,
                "Gift funds may be used for down payment and closing costs on a "
                + "primary residence purchase.",
                unitVector(0));
        saveChunk(activeDoc, 1,
                "Overtime income requires a two-year consistent history to be "
                + "considered for qualification.",
                unitVector(1));

        // Inactive document — its chunks must never be retrieved.
        MortgageDocument inactiveDoc = saveDocument("Old Guide", false, null);
        saveChunk(inactiveDoc, 0,
                "Gift funds were previously restricted under the old guideline.",
                unitVector(0));

        // Expired document — also excluded.
        MortgageDocument expiredDoc = saveDocument("Expired Overlay", true,
                LocalDate.now().minusDays(1));
        saveChunk(expiredDoc, 0,
                "Gift funds guidance from an expired overlay document.",
                unitVector(0));
    }

    @Test
    void keywordSearchFindsMatchingChunk() {
        List<ChunkSearchResult> results = chunkRepository.searchByKeyword("gift funds", 10, DEFAULT_BRAIN);

        assertFalse(results.isEmpty());
        assertTrue(results.getFirst().getContent().contains("Gift funds may be used"));
    }

    @Test
    void keywordSearchExcludesInactiveAndExpiredDocuments() {
        List<ChunkSearchResult> results = chunkRepository.searchByKeyword("gift funds", 10, DEFAULT_BRAIN);

        assertEquals(1, results.size(), "Only the active, unexpired document should match");
        assertEquals("Fannie Mae Selling Guide", results.getFirst().getSourceName());
    }

    @Test
    void vectorSearchRanksClosestEmbeddingFirst() {
        // Query with the exact embedding of chunk 0 -> cosine similarity 1.0.
        String query = EmbeddingService.toVectorLiteral(unitVector(0));
        List<ChunkSearchResult> results = chunkRepository.searchByVector(query, 10, DEFAULT_BRAIN);

        assertFalse(results.isEmpty());
        assertTrue(results.getFirst().getContent().contains("Gift funds may be used"));
        assertEquals(1.0, results.getFirst().getScore(), 0.001);
    }

    @Test
    void vectorSearchExcludesInactiveAndExpiredDocuments() {
        String query = EmbeddingService.toVectorLiteral(unitVector(0));
        List<ChunkSearchResult> results = chunkRepository.searchByVector(query, 10, DEFAULT_BRAIN);

        assertTrue(results.stream()
                .allMatch(r -> r.getSourceName().equals("Fannie Mae Selling Guide")));
    }

    @Test
    void keywordSearchReturnsCitationMetadata() {
        List<ChunkSearchResult> results = chunkRepository.searchByKeyword("overtime income", 10, DEFAULT_BRAIN);

        assertFalse(results.isEmpty());
        ChunkSearchResult hit = results.getFirst();
        assertEquals("Fannie Mae Selling Guide", hit.getSourceName());
        assertEquals("selling-guide.pdf", hit.getDocumentName());
        assertTrue(hit.getMetadataJson().contains("B3-3.1-01"));
    }

    @Test
    void searchesAreIsolatedByBrain() {
        // Register a second brain and insert a doc + chunk under it via native
        // SQL (the brain_id field is not yet JPA-mapped in this task). The "gift
        // funds" content matches the default-brain chunk inserted in setUp().
        UUID otherBrain = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        entityManager.createNativeQuery("""
                INSERT INTO brains (id, slug, display_name, is_default, is_active)
                VALUES (:id, 'other', 'Other Brain', FALSE, TRUE)
                """).setParameter("id", otherBrain).executeUpdate();
        UUID otherDocId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO brain_documents
                    (id, title, source_name, source_type, file_name, effective_date, is_active, brain_id)
                VALUES (:id, 'Other Guide', 'Other Brain Source', 'AGENCY_GUIDELINE',
                        'other.pdf', CURRENT_DATE, TRUE, :brain)
                """).setParameter("id", otherDocId).setParameter("brain", otherBrain).executeUpdate();
        UUID otherChunkId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO brain_document_chunks
                    (id, document_id, chunk_index, content, token_count, brain_id)
                VALUES (:id, :docId, 0,
                        'Gift funds policy specific to the other brain.', 40, :brain)
                """).setParameter("id", otherChunkId).setParameter("docId", otherDocId)
                .setParameter("brain", otherBrain).executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // Keyword search scoped to the OTHER brain returns ONLY its chunk, never
        // the default-brain "gift funds" chunk.
        List<ChunkSearchResult> otherHits = chunkRepository.searchByKeyword("gift funds", 10, otherBrain);
        assertEquals(1, otherHits.size(), "other-brain search must see only the other-brain chunk");
        assertEquals(otherChunkId, otherHits.getFirst().getChunkId());
        assertEquals("Other Brain Source", otherHits.getFirst().getSourceName());

        // Keyword search scoped to the DEFAULT brain never returns the other
        // brain's chunk.
        List<ChunkSearchResult> defaultHits = chunkRepository.searchByKeyword("gift funds", 10, DEFAULT_BRAIN);
        assertTrue(defaultHits.stream().noneMatch(h -> h.getChunkId().equals(otherChunkId)),
                "default-brain search must not leak the other-brain chunk");
        assertEquals("Fannie Mae Selling Guide", defaultHits.getFirst().getSourceName());
    }

    // ------------------------------------------------------------------

    private MortgageDocument saveDocument(String sourceName, boolean active,
                                          LocalDate expirationDate) {
        MortgageDocument doc = new MortgageDocument();
        doc.setTitle(sourceName + " 2026");
        doc.setSourceName(sourceName);
        doc.setSourceType(SourceType.AGENCY_GUIDELINE);
        doc.setFileName("selling-guide.pdf");
        doc.setEffectiveDate(LocalDate.now().minusMonths(6));
        doc.setExpirationDate(expirationDate);
        doc.setActive(active);
        return documentRepository.save(doc);
    }

    private void saveChunk(MortgageDocument doc, int index, String content, float[] embedding) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(doc);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setTokenCount(40);
        chunk.setMetadata(Map.of("section", "B3-3.1-01", "source_name", doc.getSourceName()));
        chunk.setEmbedding(embedding);
        chunkRepository.save(chunk);
    }

    /** Unit vector with a 1.0 in one dimension — orthogonal to other indices. */
    private float[] unitVector(int dimension) {
        float[] v = new float[1536];
        v[dimension] = 1.0f;
        return v;
    }
}
