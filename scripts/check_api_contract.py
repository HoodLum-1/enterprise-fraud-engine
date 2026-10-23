#!/usr/bin/env python3
"""Fail when docs/openapi.yaml and the Postman collection drift apart.

This is deliberately dependency-free: CI runs it without installing PyYAML or
Node. It compares HTTP method and path shape, not schemas or examples, because
the Postman collection intentionally carries curated payloads and assertions
for the same endpoint.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OPENAPI = ROOT / "docs" / "openapi.yaml"
POSTMAN = ROOT / "docs" / "postman" / "fraud-engine.postman_collection.json"
HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options"}
IGNORED_PREFIXES = ("/actuator",)


def normalise_path(path: str) -> str:
    path = path.split("?", 1)[0].split("#", 1)[0].rstrip("/") or "/"
    path = re.sub(r"\{\{[^}]+\}\}", "{}", path)
    path = re.sub(r"\{[^}]+\}", "{}", path)
    return path


def openapi_operations() -> set[tuple[str, str]]:
    operations: set[tuple[str, str]] = set()
    current_path: str | None = None
    in_paths = False
    for raw in OPENAPI.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip("\n")
        if line == "paths:":
            in_paths = True
            continue
        if in_paths and line and not line.startswith(" "):
            break
        match = re.match(r"^  (/[^:]+):\s*$", line)
        if match:
            current_path = normalise_path(match.group(1))
            continue
        if current_path:
            method = re.match(r"^    ([a-z]+):\s*$", line)
            if method and method.group(1) in HTTP_METHODS:
                operations.add((method.group(1).upper(), current_path))
    return operations


def postman_operations() -> set[tuple[str, str]]:
    collection = json.loads(POSTMAN.read_text(encoding="utf-8"))
    operations: set[tuple[str, str]] = set()

    def visit(items: list[dict]) -> None:
        for item in items:
            if "item" in item:
                visit(item["item"])
                continue
            request = item.get("request", {})
            url = request.get("url", "")
            if isinstance(url, dict):
                url = url.get("raw") or "/".join(url.get("path", []))
            path = str(url).replace("{{baseUrl}}", "")
            if "://" in path:
                path = "/" + path.split("://", 1)[1].split("/", 1)[1] if "/" in path.split("://", 1)[1] else "/"
            if not path.startswith("/"):
                path = "/" + path
            if path.startswith(IGNORED_PREFIXES):
                continue
            operations.add((str(request.get("method", "GET")).upper(), normalise_path(path)))

    visit(collection.get("item", []))
    return operations


def main() -> int:
    declared = {op for op in openapi_operations() if not op[1].startswith(IGNORED_PREFIXES)}
    exercised = postman_operations()
    missing = sorted(declared - exercised)
    stale = sorted(exercised - declared)

    if not declared:
        print("No paths found in docs/openapi.yaml", file=sys.stderr)
        return 1
    if missing:
        print("OpenAPI operations missing from the Postman collection:", file=sys.stderr)
        for method, path in missing:
            print(f"  {method:6} {path}", file=sys.stderr)
    if stale:
        print("Postman requests with no matching OpenAPI operation:", file=sys.stderr)
        for method, path in stale:
            print(f"  {method:6} {path}", file=sys.stderr)
    if missing or stale:
        return 1
    print(f"API contract OK: {len(declared)} operations match the Postman collection")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
