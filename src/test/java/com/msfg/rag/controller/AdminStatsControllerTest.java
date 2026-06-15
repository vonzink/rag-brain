package com.msfg.rag.controller;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.Brain;
import com.msfg.rag.pack.TestPacks;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.BrainResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminStatsControllerTest {

    @Test
    void statsCarryBrainIdentityAndCorpusCounts() {
        MortgageDocumentRepository docs = mock(MortgageDocumentRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        when(docs.count()).thenReturn(13L);
        when(docs.countByActiveTrue()).thenReturn(9L);
        when(chunks.count()).thenReturn(1990L);

        BrainResolver resolver = mock(BrainResolver.class);
        Brain defaultBrain = new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mountain State Financial Group");
        when(resolver.resolve(any())).thenReturn(defaultBrain);
        AdminStatsController controller =
                new AdminStatsController(TestPacks.registry(), resolver, docs, chunks);
        AdminStatsController.StatsDto stats = controller.stats(null);

        assertEquals("Mountain State Financial Group", stats.brain().companyName());
        assertEquals("mortgage", stats.brain().slug());
        assertEquals(9L, stats.corpus().activeDocuments());
        assertEquals(13L, stats.corpus().totalDocuments());
        assertEquals(1990L, stats.corpus().chunks());
    }
}
