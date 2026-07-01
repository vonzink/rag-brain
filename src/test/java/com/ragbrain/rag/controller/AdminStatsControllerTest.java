package com.ragbrain.rag.controller;

import com.ragbrain.rag.TestBrains;
import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.pack.TestPacks;
import com.ragbrain.rag.repository.DocumentChunkRepository;
import com.ragbrain.rag.repository.BrainDocumentRepository;
import com.ragbrain.rag.service.BrainResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminStatsControllerTest {

    @Test
    void statsCarryBrainIdentityAndCorpusCounts() {
        BrainDocumentRepository docs = mock(BrainDocumentRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        when(docs.countByBrainId(any())).thenReturn(13L);
        when(docs.countByBrainIdAndActiveTrue(any())).thenReturn(9L);
        when(chunks.countByBrainId(any())).thenReturn(1990L);

        BrainResolver resolver = mock(BrainResolver.class);
        Brain defaultBrain = new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mountain State Financial Group");
        when(resolver.resolve(any())).thenReturn(defaultBrain);
        AdminStatsController controller =
                new AdminStatsController(TestPacks.registry(), resolver, docs, chunks);
        AdminStatsController.StatsDto stats = controller.stats(null);

        assertEquals(TestBrains.DEFAULT_ID, stats.brain().id());
        assertEquals("Mountain State Financial Group", stats.brain().companyName());
        assertEquals("mortgage", stats.brain().slug());
        assertEquals(9L, stats.corpus().activeDocuments());
        assertEquals(13L, stats.corpus().totalDocuments());
        assertEquals(1990L, stats.corpus().chunks());
    }
}
