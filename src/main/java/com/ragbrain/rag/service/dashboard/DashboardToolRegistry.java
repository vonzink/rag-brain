package com.ragbrain.rag.service.dashboard;

import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DashboardToolRegistry {

    private final List<DashboardToolDefinition> tools = List.of(
            read("searchLoans", "Search dashboard loans visible to the current user.",
                    List.of("dashboard.loans.read"), schema("query", "limit")),
            read("getLoanSummary", "Read a summary for one loan visible to the current user.",
                    List.of("dashboard.loans.read"), schema("loanId")),
            read("listOpenTasks", "List open dashboard tasks visible to the current user.",
                    List.of("dashboard.tasks.read"), schema("assigneeId", "dueBefore")),
            read("getCalendarEvents", "Read calendar events visible to the current user.",
                    List.of("dashboard.calendar.read"), schema("from", "to")),
            read("getDocumentChecklist", "Read document checklist status for a visible file.",
                    List.of("dashboard.documents.read"), schema("loanId")),
            read("getIntegrationStatus", "Read dashboard integration health visible to the current user.",
                    List.of("dashboard.integrations.read"), schema("integrationName")),
            write("createTask", "Create a dashboard task after user confirmation.",
                    List.of("dashboard.tasks.write"), schema("title", "assigneeId", "dueDate")),
            write("updateTaskStatus", "Update a task status after user confirmation.",
                    List.of("dashboard.tasks.write"), schema("taskId", "status")),
            write("addLoanNote", "Add a note to a visible loan after user confirmation.",
                    List.of("dashboard.loans.write"), schema("loanId", "note")),
            write("scheduleFollowUp", "Schedule a follow-up after user confirmation.",
                    List.of("dashboard.calendar.write"), schema("subject", "dateTime", "attendeeId")),
            write("markDocumentReviewed", "Mark a document reviewed after user confirmation.",
                    List.of("dashboard.documents.write"), schema("documentId")),
            write("sendInternalMessage", "Send an internal dashboard message after user confirmation.",
                    List.of("dashboard.messages.write"), schema("recipientId", "message")),
            navigate("navigateToRoute", "Return a site-contained dashboard route.",
                    List.of("dashboard.navigation.use"), schema("route"))
    );

    public List<DashboardToolDefinition> list() {
        return tools;
    }

    public DashboardToolDefinition require(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dashboard tool: " + name));
    }

    private static DashboardToolDefinition read(String name, String description,
                                                List<String> permissions, Map<String, Object> schema) {
        return new DashboardToolDefinition(name, description, DashboardToolMode.READ,
                false, permissions, schema);
    }

    private static DashboardToolDefinition write(String name, String description,
                                                 List<String> permissions, Map<String, Object> schema) {
        return new DashboardToolDefinition(name, description, DashboardToolMode.WRITE,
                true, permissions, schema);
    }

    private static DashboardToolDefinition navigate(String name, String description,
                                                    List<String> permissions, Map<String, Object> schema) {
        return new DashboardToolDefinition(name, description, DashboardToolMode.NAVIGATE,
                false, permissions, schema);
    }

    private static Map<String, Object> schema(String... fields) {
        Map<String, Object> stringType = Map.of("type", "string");
        java.util.LinkedHashMap<String, Object> properties = new java.util.LinkedHashMap<>();
        for (String field : fields) {
            properties.put(field, stringType);
        }
        return Map.of(
                "type", "object",
                "properties", properties);
    }
}
