package com.ragbrain.rag.controller;

import com.ragbrain.rag.TestBrains;
import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.domain.SourceVisibility;
import com.ragbrain.rag.repository.BrainDocumentRepository;
import com.ragbrain.rag.service.BrainResolver;
import com.ragbrain.rag.service.ingestion.DocumentIngestionService;
import com.ragbrain.rag.service.retrieval.RetrievalResult;
import com.ragbrain.rag.service.retrieval.RetrievalService;
import com.ragbrain.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAdminControllerRetrievalTest {

    private final RetrievalService retrievalService = mock(RetrievalService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            mock(DocumentIngestionService.class),
            mock(BrainDocumentRepository.class),
            retrievalService,
            mock(SyncService.class),
            brainResolver);

    DocumentAdminControllerRetrievalTest() {
        when(brainResolver.resolve(any())).thenReturn(new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mortgage"));
    }

    @Test
    void testRetrievalDefaultsToAdminWideVisibility() {
        RetrievalResult result = RetrievalResult.empty();
        when(retrievalService.retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, null)).thenReturn(result);

        assertEquals(result, controller.testRetrieval("What is PMI?", null, null));
        verify(retrievalService).retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, null);
    }

    @Test
    void testRetrievalCanTargetInternalVisibility() {
        RetrievalResult result = RetrievalResult.empty();
        when(retrievalService.retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, SourceVisibility.INTERNAL))
                .thenReturn(result);

        assertEquals(result, controller.testRetrieval("What is PMI?", null, SourceVisibility.INTERNAL));
        verify(retrievalService).retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, SourceVisibility.INTERNAL);
    }
}
