package com.ragbrain.rag.service.dashboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardToolRegistryTest {

    private final DashboardToolRegistry registry = new DashboardToolRegistry();

    @Test
    void listIncludesReadWriteAndNavigationTools() {
        var tools = registry.list();
        var names = tools.stream().map(tool -> tool.name()).toList();

        assertTrue(names.contains("searchLoans"));
        assertTrue(names.contains("listOpenTasks"));
        assertTrue(names.contains("createTask"));
        assertTrue(names.contains("updateTaskStatus"));
        assertTrue(names.contains("navigateToRoute"));
    }

    @Test
    void readToolsDoNotRequireConfirmationAndHaveReadPermissions() {
        var tool = registry.require("searchLoans");

        assertEquals(DashboardToolMode.READ, tool.mode());
        assertFalse(tool.confirmationRequired());
        assertTrue(tool.requiredPermissions().contains("dashboard.loans.read"));
    }

    @Test
    void writeToolsRequireConfirmationAndWritePermissions() {
        var tool = registry.require("createTask");

        assertEquals(DashboardToolMode.WRITE, tool.mode());
        assertTrue(tool.confirmationRequired());
        assertTrue(tool.requiredPermissions().contains("dashboard.tasks.write"));
    }

    @Test
    void navigationToolIsSiteContained() {
        var tool = registry.require("navigateToRoute");

        assertEquals(DashboardToolMode.NAVIGATE, tool.mode());
        assertFalse(tool.confirmationRequired());
        assertTrue(tool.requiredPermissions().contains("dashboard.navigation.use"));
    }

    @Test
    void requireRejectsUnknownTool() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.require("deleteEverything"));

        assertEquals("Unknown dashboard tool: deleteEverything", ex.getMessage());
    }
}
