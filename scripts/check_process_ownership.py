#!/usr/bin/env python3
"""Fail if a screen creeps back into the process-scoped runtimes.

Lyrebird runs its ground-station runtimes on the aircraft's process, not on a screen: an RC whose
Flight Deck is closed, or whose activity is being restarted, keeps serving HTTP, MAVLink,
telemetry and video. That property is what the B5 batches established, and it is exactly the kind
of property that erodes silently — one more member read from an attached activity, one more
`?: return` when nobody is, and the screenless case quietly answers nothing while every test and
every bench session with a screen open still passes.

Two rules, both enforced over the app's Kotlin sources:

  1. The process composition package (`src/v5/java/com/lyrebird/rc/server/`) must not mention the
     screen at all: no `FlightDeckActivity`, no `android.app.Activity`. What a screen does with a
     runtime goes through the `*Ui` interfaces.

  2. Every UI contract (`CommandSurfaceUi`, `StreamingRuntimeUi`, `ObstacleGuardUi`,
     `NetworkRuntimeCallbacks`, `DetectionRuntimeCallbacks`) must have exactly one implementation
     and it must be the Flight Deck activity. A second implementer means a second owner, which is
     how two consumers came to derive the aircraft's name differently and a dashboard ended up
     subscribing to a path nothing published to.

Usage: python3 scripts/check_process_ownership.py [app-module-dir]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# The process composition root: what must stay screen-free.
PROCESS_DIR = "src/v5/java/com/lyrebird/rc/server"

# Production source sets. Test fakes implement these contracts too, deliberately and safely;
# only shipped code has to keep one owner.
PRODUCTION_DIRS = ("src/main", "src/v5", "src/v4")

# Contracts a screen implements, and the only file allowed to implement them.
UI_CONTRACTS = (
    "CommandSurfaceUi",
    "StreamingRuntimeUi",
    "ObstacleGuardUi",
    "NetworkRuntimeCallbacks",
    "DetectionRuntimeCallbacks",
)
SCREEN_FILE = "FlightDeckActivity.kt"

SCREEN_MARKERS = ("FlightDeckActivity", "android.app.Activity")

# A declaration header: `class X ... {` or `object X ... {`. The supertype list (and primary
# constructor parameters) sit between the name and the first brace, which is all a checker needs
# — an interface named in a constructor parameter list would be a coincidence, and a loud one.
DECL_HEADER = re.compile(r"\b(?:class|object)\s+([A-Za-z_][A-Za-z0-9_]*)([^{;=]*)\{", re.DOTALL)

# `object : SomeContract {` — an anonymous implementation, which the rule above cannot see by
# declaration name.
ANON_IMPL = re.compile(r"\bobject\s*:\s*([A-Za-z_][A-Za-z0-9_]*)")

KOTLIN_DECL = re.compile(
    r"^\s*(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|"
    r"value|annotation|inline|external)\s+)*"
    r"(class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)
PACKAGE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.MULTILINE)


def strip_comments_and_strings(text: str) -> str:
    """Replace comment and string-literal contents with spaces, keeping line numbers.

    Same job as the SDK-free checker's copy: a contract named in a KDoc paragraph or in a log
    message is not an implementation of it.
    """
    out: list[str] = []
    i, n = 0, len(text)
    state = "code"
    depth = 0
    quote = ""
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line_comment"
                out.append("  ")
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block_comment"
                depth = 1
                out.append("  ")
                i += 2
                continue
            if ch == '"':
                if text[i : i + 3] == '"""':
                    state = "triple"
                    out.append(" " * 3)
                    i += 3
                    continue
                state = "string"
                quote = ch
                out.append(" ")
                i += 1
                continue
            if ch == "'":
                state = "string"
                quote = ch
                out.append(" ")
                i += 1
                continue
            out.append(ch)
            i += 1
        elif state == "line_comment":
            if ch == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
        elif state == "block_comment":
            if ch == "/" and nxt == "*":
                depth += 1
                out.append("  ")
                i += 2
                continue
            if ch == "*" and nxt == "/":
                depth -= 1
                out.append("  ")
                i += 2
                if depth == 0:
                    state = "code"
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
        elif state in ("string", "triple"):
            if state == "triple":
                if text[i : i + 3] == '"""':
                    state = "code"
                    out.append(" " * 3)
                    i += 3
                    continue
            else:
                if ch == "\\":
                    out.append("  ")
                    i += 2
                    continue
                if ch == quote:
                    state = "code"
            out.append("\n" if ch == "\n" else " ")
            i += 1
    return "".join(out)


def source_files(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*") if p.suffix == ".kt")


def production_files(app_dir: Path) -> list[Path]:
    files: list[Path] = []
    for name in PRODUCTION_DIRS:
        directory = app_dir / name
        if directory.is_dir():
            files.extend(source_files(directory))
    return sorted(files)


def contract_implementations(files: list[Path]) -> dict[str, list[str]]:
    """contract -> ['path:line', ...] for every declaration naming it as a supertype."""
    found: dict[str, list[str]] = {name: [] for name in UI_CONTRACTS}
    for path in files:
        text = strip_comments_and_strings(path.read_text(encoding="utf-8"))
        for match in DECL_HEADER.finditer(text):
            header = match.group(2)
            line = text[: match.start()].count("\n") + 1
            for contract in UI_CONTRACTS:
                if re.search(rf"\b{contract}\b", header):
                    found[contract].append(f"{path}:{line} ({match.group(1)})")
        for match in ANON_IMPL.finditer(text):
            contract = match.group(1)
            if contract in found:
                line = text[: match.start()].count("\n") + 1
                found[contract].append(f"{path}:{line} (anonymous object)")
    return found


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    app_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else repo_root / "LyrebirdApp/lyrebird-app"
    src_root = app_dir / "src"
    if not src_root.is_dir():
        print(f"error: no source root at {src_root}")
        return 2

    violations: list[str] = []

    process_root = app_dir / PROCESS_DIR
    if not process_root.is_dir():
        print(f"error: no process package at {process_root}")
        return 2

    for path in source_files(process_root):
        cleaned = strip_comments_and_strings(path.read_text(encoding="utf-8"))
        for marker in SCREEN_MARKERS:
            if marker in cleaned:
                line = cleaned[: cleaned.index(marker)].count("\n") + 1
                violations.append(
                    f"{path.relative_to(repo_root)}:{line}: the process package references "
                    f"'{marker}'; a screen's work belongs behind a *Ui contract"
                )

    implementations = contract_implementations(production_files(app_dir))
    for contract in UI_CONTRACTS:
        sites = implementations[contract]
        if not sites:
            violations.append(
                f"{contract}: no implementation found; if the contract was removed, drop it "
                f"from this checker"
            )
            continue
        for site in sites:
            if SCREEN_FILE not in site:
                violations.append(
                    f"{contract} is implemented outside {SCREEN_FILE} ({site}); the screen is "
                    f"its only allowed owner"
                )

    if violations:
        print("process-ownership check failed:")
        for violation in violations:
            print(f"  {violation}")
        return 1

    # Show the declared contracts so a reader can see what the rule is holding to.
    names = ", ".join(f"{c} ({len(implementations[c])})" for c in UI_CONTRACTS)
    print(f"process-ownership check: {len(source_files(process_root))} process files screen-free; {names}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
