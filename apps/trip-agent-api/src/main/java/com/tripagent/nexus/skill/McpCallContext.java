package com.tripagent.nexus.skill;

import java.util.UUID;

public record McpCallContext(String agentName, UUID ownerTaskId) {}
