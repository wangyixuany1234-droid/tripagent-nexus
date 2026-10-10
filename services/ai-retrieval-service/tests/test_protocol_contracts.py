import json
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CONTRACT_ROOT = REPOSITORY_ROOT / "packages" / "api-contract"

EXPECTED_TOOLS = {
    "place_search",
    "weather_query",
    "route_calculate",
    "exchange_rate",
    "opening_hours",
    "booking_parse",
    "budget_validate",
    "itinerary_store",
}
EXPECTED_AGENTS = {
    "RequirementAgent",
    "ResearchAgent",
    "TransportAgent",
    "AccommodationAgent",
    "RecommendationAgent",
    "BudgetAgent",
    "ReviewAgent",
    "CoordinatorAgent",
}


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def test_mcp_tools_have_closed_bounded_and_idempotent_contracts() -> None:
    contract = _load(CONTRACT_ROOT / "mcp-tools.schema.json")
    tools = contract["x-tools"]
    definitions = contract["$defs"]

    assert set(tools) == EXPECTED_TOOLS
    assert definitions["toolResult"]["additionalProperties"] is False
    assert set(definitions["toolResult"]["required"]) == {
        "status",
        "providerMode",
        "data",
        "warnings",
    }
    for name, tool in tools.items():
        assert tool["input"] == f"#/$defs/{name}_input"
        assert tool["output"] == "#/$defs/toolResult"
        assert tool["idempotent"] is True
        input_schema = definitions[f"{name}_input"]
        assert input_schema["type"] == "object"
        assert input_schema["additionalProperties"] is False
        assert input_schema["required"]
    assert tools["itinerary_store"]["readOnly"] is False
    assert all(
        tool["readOnly"] is True
        for name, tool in tools.items()
        if name != "itinerary_store"
    )


def test_a2a_agents_are_physically_separate_and_privacy_bounded() -> None:
    agent_root = CONTRACT_ROOT / "agents"
    common = _load(agent_root / "common.schema.json")
    agent_contracts = [
        _load(path) for path in sorted(agent_root.glob("*-agent.schema.json"))
    ]
    cards = [contract["x-agent-card"] for contract in agent_contracts]

    assert {card["name"] for card in cards} == EXPECTED_AGENTS
    assert len(agent_contracts) == len(EXPECTED_AGENTS)
    assert set(common["$defs"]["agentName"]["enum"]) == EXPECTED_AGENTS
    assert set(common["$defs"]["mcpToolName"]["enum"]) == EXPECTED_TOOLS
    assert "privacyLabels" in common["$defs"]["contextEnvelope"]["required"]
    assert common["$defs"]["contextEnvelope"]["additionalProperties"] is False
    assert common["$defs"]["resultEnvelope"]["additionalProperties"] is False

    for contract, card in zip(agent_contracts, cards, strict=True):
        assert card["skills"]
        assert set(card["skills"]) <= EXPECTED_TOOLS
        assert 1 <= card["retry"]["maxAttempts"] <= 3
        assert 0 < card["timeoutMs"] <= 120_000
        assert card["promptTemplateVersion"]
        output_extension = contract["$defs"]["output"]["allOf"][1]["properties"]
        assert output_extension["agentName"]["const"] == card["name"]
        assert output_extension["result"]["$ref"] == "#/$defs/result"
