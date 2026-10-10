import json
from collections import defaultdict
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Protocol

from trip_agent_ai.graph.connection import Neo4jConnection
from trip_agent_ai.schemas.retrieval import (
    GraphNodeInput,
    GraphNodeType,
    GraphProjectionInput,
    GraphRelationshipInput,
    GraphRelationshipType,
    GraphScope,
    GraphSearchRequest,
    RetrievalStrategy,
)

NODE_LABELS = {
    GraphNodeType.CITY: "City",
    GraphNodeType.REGION: "Region",
    GraphNodeType.ATTRACTION: "Attraction",
    GraphNodeType.RESTAURANT: "Restaurant",
    GraphNodeType.HOTEL: "Hotel",
    GraphNodeType.STATION: "Station",
    GraphNodeType.AIRPORT: "Airport",
    GraphNodeType.TRANSIT_LINE: "TransitLine",
}
RELATIONSHIP_TYPES = {item: item.value for item in GraphRelationshipType}


@dataclass(slots=True)
class GraphHit:
    record: dict[str, Any]
    score: float
    strategy: RetrievalStrategy


class GraphStore(Protocol):
    async def ingest(self, projection: GraphProjectionInput) -> tuple[int, int]: ...

    async def search(
        self,
        request: GraphSearchRequest,
    ) -> tuple[GraphScope, list[GraphHit]]: ...


class Neo4jGraphStore:
    def __init__(self, connection: Neo4jConnection) -> None:
        self._connection = connection
        self._constraint_ready = False

    async def ingest(self, projection: GraphProjectionInput) -> tuple[int, int]:
        await self._ensure_constraint()
        nodes_by_type: dict[GraphNodeType, list[dict[str, Any]]] = defaultdict(list)
        for node in projection.nodes:
            nodes_by_type[node.type].append(_node_properties(node))
        for node_type, nodes in nodes_by_type.items():
            label = NODE_LABELS[node_type]
            await self._connection.execute_query(
                f"""
                UNWIND $nodes AS item
                MERGE (node:TravelEntity:{label} {{nodeId: item.nodeId}})
                SET node += item
                """,
                nodes=nodes,
            )

        relationships_by_type: dict[
            GraphRelationshipType,
            list[dict[str, Any]],
        ] = defaultdict(list)
        for relationship in projection.relationships:
            relationships_by_type[relationship.type].append(
                _relationship_properties(relationship)
            )
        for relationship_type, relationships in relationships_by_type.items():
            cypher_type = RELATIONSHIP_TYPES[relationship_type]
            await self._connection.execute_query(
                f"""
                UNWIND $relationships AS item
                MATCH (source:TravelEntity {{nodeId: item.fromNodeId}})
                MATCH (target:TravelEntity {{nodeId: item.toNodeId}})
                MERGE (source)-[relation:{cypher_type}]->(target)
                SET relation.distanceMeters = item.distanceMeters,
                    relation.durationMinutes = item.durationMinutes,
                    relation.metadata = item.metadata
                """,
                relationships=relationships,
            )
        return len(projection.nodes), len(projection.relationships)

    async def search(
        self,
        request: GraphSearchRequest,
    ) -> tuple[GraphScope, list[GraphHit]]:
        scope = resolve_graph_scope(request)
        query, strategy = _query_for_scope(scope)
        result = await self._connection.execute_query(
            query,
            query=request.query,
            anchor=request.anchorEntity or "",
            city=request.city or "",
            region=request.region or "",
            language=request.language,
            asOf=(request.travelDate or datetime.now(UTC).date()).isoformat(),
            limit=request.maxResults,
        )
        hits = [
            GraphHit(
                record=dict(record["candidate"]),
                score=float(record["score"]),
                strategy=strategy,
            )
            for record in result.records
        ]
        return scope, hits

    async def _ensure_constraint(self) -> None:
        if self._constraint_ready:
            return
        await self._connection.execute_query(
            """
            CREATE CONSTRAINT travel_entity_node_id IF NOT EXISTS
            FOR (node:TravelEntity) REQUIRE node.nodeId IS UNIQUE
            """
        )
        self._constraint_ready = True


class InMemoryGraphStore:
    def __init__(self) -> None:
        self._nodes: dict[str, GraphNodeInput] = {}
        self._relationships: dict[
            tuple[str, str, GraphRelationshipType],
            GraphRelationshipInput,
        ] = {}

    async def ingest(self, projection: GraphProjectionInput) -> tuple[int, int]:
        for node in projection.nodes:
            self._nodes[node.nodeId] = node
        for relationship in projection.relationships:
            key = (
                relationship.fromNodeId,
                relationship.toNodeId,
                relationship.type,
            )
            self._relationships[key] = relationship
        return len(projection.nodes), len(projection.relationships)

    async def search(
        self,
        request: GraphSearchRequest,
    ) -> tuple[GraphScope, list[GraphHit]]:
        scope = resolve_graph_scope(request)
        nodes = [
            node
            for node in self._nodes.values()
            if _node_matches_filters(node, request)
        ]
        if scope is GraphScope.LOCAL:
            hits = self._search_local(nodes, request)
        elif scope is GraphScope.REGION:
            hits = self._search_region(nodes, request)
        else:
            hits = self._search_transit(nodes, request)
        return scope, hits[: request.maxResults]

    def _search_local(
        self,
        nodes: list[GraphNodeInput],
        request: GraphSearchRequest,
    ) -> list[GraphHit]:
        anchor = _find_anchor(nodes, request)
        if anchor is None:
            return []
        hits: list[GraphHit] = []
        for relationship in self._relationships.values():
            if relationship.type not in {
                GraphRelationshipType.NEAR,
                GraphRelationshipType.CONNECTED_TO,
                GraphRelationshipType.LOCATED_IN,
            }:
                continue
            candidate_id = _other_endpoint(relationship, anchor.nodeId)
            if candidate_id is None:
                continue
            candidate = self._nodes.get(candidate_id)
            if candidate is None or candidate not in nodes:
                continue
            distance = relationship.distanceMeters or 5000
            relation_score = {
                GraphRelationshipType.NEAR: 0.97,
                GraphRelationshipType.CONNECTED_TO: 0.88,
                GraphRelationshipType.LOCATED_IN: 0.78,
            }[relationship.type]
            distance_penalty = min(distance / 100_000, 0.15)
            hits.append(
                GraphHit(
                    record=_node_properties(candidate),
                    score=max(0.0, relation_score - distance_penalty),
                    strategy=RetrievalStrategy.LOCAL_GRAPH,
                )
            )
        return _deduplicate_hits(hits)

    def _search_region(
        self,
        nodes: list[GraphNodeInput],
        request: GraphSearchRequest,
    ) -> list[GraphHit]:
        allowed_ids = {node.nodeId for node in nodes}
        region_ids = {
            node.nodeId for node in nodes if node.type is GraphNodeType.REGION
        }
        related_ids = {
            relationship.fromNodeId
            for relationship in self._relationships.values()
            if relationship.type is GraphRelationshipType.LOCATED_IN
            and relationship.toNodeId in region_ids
        }
        selected_ids = region_ids | related_ids
        hits = [
            GraphHit(
                record=_node_properties(node),
                score=0.96 if node.type is GraphNodeType.REGION else 0.82,
                strategy=RetrievalStrategy.REGION_GRAPH,
            )
            for node in nodes
            if node.nodeId in selected_ids and node.nodeId in allowed_ids
        ]
        return _deduplicate_hits(hits)

    def _search_transit(
        self,
        nodes: list[GraphNodeInput],
        request: GraphSearchRequest,
    ) -> list[GraphHit]:
        del request
        transit_ids = {
            endpoint
            for relationship in self._relationships.values()
            if relationship.type is GraphRelationshipType.CONNECTED_TO
            for endpoint in (relationship.fromNodeId, relationship.toNodeId)
        }
        hits = [
            GraphHit(
                record=_node_properties(node),
                score=0.9,
                strategy=RetrievalStrategy.TRANSIT_GRAPH,
            )
            for node in nodes
            if node.nodeId in transit_ids
            or node.type
            in {
                GraphNodeType.STATION,
                GraphNodeType.AIRPORT,
                GraphNodeType.TRANSIT_LINE,
            }
        ]
        return _deduplicate_hits(hits)


def resolve_graph_scope(request: GraphSearchRequest) -> GraphScope:
    if request.scope is not GraphScope.AUTO:
        return request.scope
    query = request.query.lower()
    if any(term in query for term in ("附近", "周边", "顺路", "步行")):
        return GraphScope.LOCAL
    if any(term in query for term in ("分区", "区域", "片区", "街区")):
        return GraphScope.REGION
    if any(term in query for term in ("地铁", "车站", "机场", "换乘", "线路")):
        return GraphScope.TRANSIT
    return GraphScope.REGION


def _query_for_scope(scope: GraphScope) -> tuple[str, RetrievalStrategy]:
    validity = """
      AND candidate.language = $language
      AND (candidate.validFrom = '' OR candidate.validFrom <= $asOf)
      AND (candidate.validTo = '' OR candidate.validTo >= $asOf)
    """
    if scope is GraphScope.LOCAL:
        return (
            f"""
            MATCH (anchor:TravelEntity)
            WHERE ($city = '' OR anchor.city = $city)
              AND (
                ($anchor <> '' AND toLower(anchor.name) = toLower($anchor))
                OR ($anchor = '' AND toLower($query) CONTAINS toLower(anchor.name))
              )
            WITH anchor ORDER BY size(anchor.name) DESC LIMIT 1
            MATCH (anchor)-[relation:NEAR|CONNECTED_TO|LOCATED_IN]-(candidate)
            WHERE candidate:TravelEntity
              AND candidate.nodeId <> anchor.nodeId
              AND ($city = '' OR candidate.city = $city)
              AND ($region = '' OR candidate.region = $region)
              {validity}
            RETURN candidate {{.*}} AS candidate,
              CASE type(relation)
                WHEN 'NEAR' THEN 0.97
                WHEN 'CONNECTED_TO' THEN 0.88
                ELSE 0.78
              END - CASE
                WHEN coalesce(relation.distanceMeters, 5000) > 15000 THEN 0.15
                ELSE coalesce(relation.distanceMeters, 5000) / 100000.0
              END
              AS score
            ORDER BY score DESC, candidate.name ASC
            LIMIT $limit
            """,
            RetrievalStrategy.LOCAL_GRAPH,
        )
    if scope is GraphScope.TRANSIT:
        return (
            f"""
            MATCH (candidate:TravelEntity)
            WHERE candidate.nodeType IN ['STATION', 'AIRPORT', 'TRANSIT_LINE']
              AND ($city = '' OR candidate.city = $city)
              AND ($region = '' OR candidate.region = $region)
              {validity}
            RETURN candidate {{.*}} AS candidate, 0.90 AS score
            ORDER BY candidate.name ASC
            LIMIT $limit
            """,
            RetrievalStrategy.TRANSIT_GRAPH,
        )
    return (
        f"""
        MATCH (area:TravelEntity:Region)
        WHERE ($city = '' OR area.city = $city)
          AND ($region = '' OR area.name = $region)
        OPTIONAL MATCH (candidate:TravelEntity)-[:LOCATED_IN]->(area)
        WITH area, collect(candidate) AS areaCandidates
        UNWIND ([area] + areaCandidates) AS candidate
        WITH DISTINCT candidate
        WHERE candidate.nodeType IN ['REGION', 'ATTRACTION', 'RESTAURANT', 'HOTEL']
          {validity}
        RETURN candidate {{.*}} AS candidate,
          CASE candidate.nodeType WHEN 'REGION' THEN 0.96 ELSE 0.82 END AS score
        ORDER BY score DESC, candidate.region ASC, candidate.name ASC
        LIMIT $limit
        """,
        RetrievalStrategy.REGION_GRAPH,
    )


def _node_properties(node: GraphNodeInput) -> dict[str, Any]:
    return {
        "nodeId": node.nodeId,
        "nodeType": node.type.value,
        "name": node.name,
        "sourceId": node.sourceId,
        "sourceType": node.sourceType.value,
        "title": node.title,
        "excerpt": node.excerpt,
        "city": node.city,
        "region": node.region or "",
        "validFrom": node.validFrom.isoformat() if node.validFrom else "",
        "validTo": node.validTo.isoformat() if node.validTo else "",
        "language": node.language,
        "updatedAt": node.updatedAt.astimezone(UTC).isoformat(),
        "metadata": json.dumps(
            node.metadata,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ),
    }


def _relationship_properties(
    relationship: GraphRelationshipInput,
) -> dict[str, Any]:
    return {
        "fromNodeId": relationship.fromNodeId,
        "toNodeId": relationship.toNodeId,
        "distanceMeters": relationship.distanceMeters,
        "durationMinutes": relationship.durationMinutes,
        "metadata": json.dumps(
            relationship.metadata,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ),
    }


def _node_matches_filters(
    node: GraphNodeInput,
    request: GraphSearchRequest,
) -> bool:
    as_of = request.travelDate or datetime.now(UTC).date()
    return (
        (not request.city or node.city == request.city)
        and (not request.region or node.region == request.region)
        and node.language == request.language
        and (node.validFrom is None or node.validFrom <= as_of)
        and (node.validTo is None or node.validTo >= as_of)
    )


def _find_anchor(
    nodes: list[GraphNodeInput],
    request: GraphSearchRequest,
) -> GraphNodeInput | None:
    if request.anchorEntity:
        exact = [
            node
            for node in nodes
            if node.name.casefold() == request.anchorEntity.casefold()
        ]
        if exact:
            return exact[0]
    candidates = [
        node for node in nodes if node.name.casefold() in request.query.casefold()
    ]
    return max(candidates, key=lambda node: len(node.name), default=None)


def _other_endpoint(
    relationship: GraphRelationshipInput,
    node_id: str,
) -> str | None:
    if relationship.fromNodeId == node_id:
        return relationship.toNodeId
    if relationship.toNodeId == node_id:
        return relationship.fromNodeId
    return None


def _deduplicate_hits(hits: list[GraphHit]) -> list[GraphHit]:
    best: dict[str, GraphHit] = {}
    for hit in hits:
        node_id = str(hit.record["nodeId"])
        if node_id not in best or hit.score > best[node_id].score:
            best[node_id] = hit
    return sorted(best.values(), key=lambda item: item.score, reverse=True)
