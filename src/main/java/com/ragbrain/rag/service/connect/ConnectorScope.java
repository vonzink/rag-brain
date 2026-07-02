package com.ragbrain.rag.service.connect;

import java.util.List;

public final class ConnectorScope {
    private ConnectorScope() {}

    public static final String BRAINS_LIST = "brains:list";
    public static final String BRAIN_READ = "brain:read";
    public static final String ASK_PUBLIC = "ask:public";
    public static final String RETRIEVE_PUBLIC = "retrieve:public";
    public static final String CITATIONS_READ = "citations:read";
    public static final String READINESS_READ = "readiness:read";
    public static final String DASHBOARD_ASK = "dashboard:ask";
    public static final String DASHBOARD_TOOLS_LIST = "dashboard:tools:list";
    public static final String DASHBOARD_TOOLS_READ = "dashboard:tools:read";
    public static final String DASHBOARD_TOOLS_WRITE = "dashboard:tools:write";

    public static final List<String> MVP_SCOPES = List.of(
            BRAINS_LIST, BRAIN_READ, ASK_PUBLIC, RETRIEVE_PUBLIC,
            CITATIONS_READ, READINESS_READ,
            DASHBOARD_ASK, DASHBOARD_TOOLS_LIST, DASHBOARD_TOOLS_READ, DASHBOARD_TOOLS_WRITE);
}
