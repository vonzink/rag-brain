package com.msfg.rag.controller;

import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.BrainResolver;
import com.msfg.rag.service.ingestion.DocumentIngestionService;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.sync.SyncReport;
import com.msfg.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import com.msfg.rag.TestBrains;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAdminControllerSyncTest {

    private final SyncService syncService = mock(SyncService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            mock(DocumentIngestionService.class),
            mock(MortgageDocumentRepository.class),
            mock(RetrievalService.class),
            syncService,
            brainResolver);

    DocumentAdminControllerSyncTest() {
        com.msfg.rag.domain.Brain brain = new com.msfg.rag.domain.Brain(TestBrains.DEFAULT_ID, "mortgage", "Mortgage");
        when(brainResolver.resolve(any())).thenReturn(brain);
    }

    @Test
    void syncPassesDryRunFlagThrough() {
        SyncReport report = new SyncReport(true, Map.of("skip", 1), List.of());
        when(syncService.sync(eq(true), eq(TestBrains.DEFAULT_ID))).thenReturn(report);

        assertEquals(report, controller.sync(true, null));
        verify(syncService).sync(eq(true), eq(TestBrains.DEFAULT_ID));
    }

    @Test
    void syncDefaultsToExecute() {
        SyncReport report = new SyncReport(false, Map.of(), List.of());
        when(syncService.sync(eq(false), eq(TestBrains.DEFAULT_ID))).thenReturn(report);

        assertEquals(report, controller.sync(false, null));
    }
}
