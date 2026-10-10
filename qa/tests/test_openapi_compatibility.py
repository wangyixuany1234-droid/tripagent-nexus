import copy
import importlib.util
import unittest
from pathlib import Path

import yaml

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CHECKER_PATH = REPOSITORY_ROOT / "qa/scripts/check_openapi_compatibility.py"
CURRENT_CONTRACT = REPOSITORY_ROOT / "packages/api-contract/openapi.yaml"


def load_checker():
    spec = importlib.util.spec_from_file_location("openapi_compatibility", CHECKER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载契约检查器：{CHECKER_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def baseline_contract() -> dict:
    return {
        "openapi": "3.1.0",
        "paths": {
            "/things": {
                "post": {
                    "parameters": [
                        {
                            "name": "mode",
                            "in": "query",
                            "required": False,
                            "schema": {"type": "string", "enum": ["FAST", "SAFE"]},
                        }
                    ],
                    "requestBody": {
                        "required": True,
                        "content": {
                            "application/json": {
                                "schema": {"$ref": "#/components/schemas/Thing"}
                            }
                        },
                    },
                    "responses": {
                        "201": {
                            "content": {
                                "application/json": {
                                    "schema": {"$ref": "#/components/schemas/Thing"}
                                }
                            }
                        },
                        "422": {"$ref": "#/components/responses/Invalid"},
                    },
                }
            }
        },
        "components": {
            "schemas": {
                "Thing": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["id"],
                    "properties": {
                        "id": {"type": "string", "format": "uuid"},
                        "state": {"type": "string", "enum": ["NEW", "READY"]},
                        "name": {"type": "string", "minLength": 1, "maxLength": 100},
                    },
                }
            },
            "responses": {"Invalid": {"description": "Invalid request"}},
        },
    }


class OpenApiCompatibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.checker = load_checker()

    def test_optional_additions_are_backward_compatible(self) -> None:
        baseline = baseline_contract()
        candidate = copy.deepcopy(baseline)
        candidate["components"]["schemas"]["Thing"]["properties"]["note"] = {
            "type": "string"
        }
        candidate["paths"]["/things"]["get"] = {"responses": {"200": {}}}

        self.assertEqual(self.checker.find_breaking_changes(baseline, candidate), [])

    def test_union_that_preserves_the_baseline_type_is_backward_compatible(
        self,
    ) -> None:
        baseline = baseline_contract()
        candidate = copy.deepcopy(baseline)
        identifier = candidate["components"]["schemas"]["Thing"]["properties"]["id"]
        candidate["components"]["schemas"]["Thing"]["properties"]["id"] = {
            "anyOf": [identifier, {"type": "integer"}]
        }

        self.assertEqual(self.checker.find_breaking_changes(baseline, candidate), [])

    def test_union_without_the_baseline_type_is_breaking(self) -> None:
        baseline = baseline_contract()
        candidate = copy.deepcopy(baseline)
        candidate["components"]["schemas"]["Thing"]["properties"]["id"] = {
            "anyOf": [{"type": "integer"}, {"type": "number"}]
        }

        self.assertTrue(self.checker.find_breaking_changes(baseline, candidate))

    def test_breaking_mutations_are_detected(self) -> None:
        mutations = {
            "field deletion": lambda contract: contract["components"]["schemas"][
                "Thing"
            ]["properties"].pop("name"),
            "field rename": lambda contract: contract["components"]["schemas"]["Thing"][
                "properties"
            ].update(
                {
                    "displayName": contract["components"]["schemas"]["Thing"][
                        "properties"
                    ].pop("name")
                }
            ),
            "type change": lambda contract: contract["components"]["schemas"]["Thing"][
                "properties"
            ]["id"].update({"type": "integer"}),
            "required change": lambda contract: contract["components"]["schemas"][
                "Thing"
            ]["required"].append("name"),
            "enum narrowing": lambda contract: contract["components"]["schemas"][
                "Thing"
            ]["properties"]["state"].update({"enum": ["NEW"]}),
            "maximum tightening": lambda contract: contract["components"]["schemas"][
                "Thing"
            ]["properties"]["name"].update({"maxLength": 50}),
            "response removal": lambda contract: contract["paths"]["/things"]["post"][
                "responses"
            ].pop("422"),
            "content type removal": lambda contract: contract["paths"]["/things"][
                "post"
            ]["responses"]["201"]["content"].pop("application/json"),
        }

        for name, mutate in mutations.items():
            with self.subTest(name=name):
                baseline = baseline_contract()
                candidate = copy.deepcopy(baseline)
                mutate(candidate)
                self.assertTrue(
                    self.checker.find_breaking_changes(baseline, candidate),
                    f"mutation was not rejected: {name}",
                )

    def test_public_contract_declares_boundary_and_error_constraints(self) -> None:
        contract = yaml.safe_load(CURRENT_CONTRACT.read_text(encoding="utf-8"))
        schemas = contract["components"]["schemas"]
        create_trip = schemas["CreateTripRequest"]
        error = schemas["ApiError"]

        self.assertFalse(create_trip["additionalProperties"])
        self.assertEqual(
            set(create_trip["required"]),
            {"userId", "title", "timeZone"},
        )
        self.assertEqual(create_trip["properties"]["userId"]["format"], "uuid")
        requirement_fields = schemas["TravelRequirementFields"]
        self.assertEqual(
            requirement_fields["properties"]["destinations"]["minItems"], 1
        )
        self.assertGreater(
            requirement_fields["properties"]["destinations"]["maxItems"],
            requirement_fields["properties"]["destinations"]["minItems"],
        )
        self.assertEqual(schemas["TaskStatus"]["type"], "string")
        self.assertGreater(len(schemas["TaskStatus"]["enum"]), 1)
        self.assertTrue(
            {"code", "message", "traceId", "timestamp"} <= set(error["required"])
        )
        self.assertFalse(error["additionalProperties"])


if __name__ == "__main__":
    unittest.main()
