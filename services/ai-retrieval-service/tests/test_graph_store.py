from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from trip_agent_ai.graph.store import Neo4jGraphStore, resolve_graph_scope
from trip_agent_ai.schemas.retrieval import (
    GraphNodeInput,
    GraphNodeType,
    GraphProjectionInput,
    GraphRelationshipInput,
    GraphRelationshipType,
    GraphScope,
    GraphSearchRequest,
    KnowledgeSourceType,
)


class RecordingConnection:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, object]]] = []

    async def execute_query(self, query: str, **parameters: object):
        self.calls.append((query, parameters))
        return SimpleNamespace(records=[])


@pytest.mark.asyncio
async def test_neo4j_ingest_uses_whitelisted_labels_and_relationships() -> None:
    connection = RecordingConnection()
    store = Neo4jGraphStore(connection)  # type: ignore[arg-type]
    nodes = [_node(node_type, index) for index, node_type in enumerate(GraphNodeType)]
    relationships = [
        GraphRelationshipInput(
            fromNodeId=nodes[index].nodeId,
            toNodeId=nodes[index + 1].nodeId,
            type=relationship_type,
            metadata={},
        )
        for index, relationship_type in enumerate(GraphRelationshipType)
    ]

    counts = await store.ingest(
        GraphProjectionInput(nodes=nodes, relationships=relationships)
    )

    assert counts == (len(GraphNodeType), len(GraphRelationshipType))
    emitted = "\n".join(query for query, _ in connection.calls)
    for label in (
        "City",
        "Region",
        "Attraction",
        "Restaurant",
        "Hotel",
        "Station",
        "Airport",
        "TransitLine",
    ):
        assert f"TravelEntity:{label}" in emitted
    for relationship_type in GraphRelationshipType:
        assert f"relation:{relationship_type.value}" in emitted


@pytest.mark.parametrize(
    ("query", "scope"),
    [
        ("故宫附近有哪些景点", GraphScope.LOCAL),
        ("北京三天如何分区游览", GraphScope.REGION),
        ("机场到市区如何换乘", GraphScope.TRANSIT),
    ],
)
def test_graph_scope_router(query: str, scope: GraphScope) -> None:
    assert resolve_graph_scope(GraphSearchRequest(query=query)) is scope


def _node(node_type: GraphNodeType, index: int) -> GraphNodeInput:
    return GraphNodeInput(
        nodeId=f"node-{index}",
        type=node_type,
        name=f"Node {index}",
        sourceId=f"source-{index}",
        sourceType=KnowledgeSourceType.CITY_GUIDE,
        title=f"Node {index}",
        excerpt="Synthetic graph fixture.",
        city="北京",
        language="zh-CN",
        updatedAt=datetime(2026, 9, 4, tzinfo=UTC),
        metadata={},
    )
