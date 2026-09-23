#!/usr/bin/env python3
"""Fail if `lyrebird-app`'s main source set depends on a specific SDK generation.

The shared application code (src/main) is what a second SDK adapter has to compile against:
it may use neutral ports and anything under :lyrebird-core, but it must not reference DJI
classes nor any class declared only in a flavor source set (src/v5, later src/v4). Without
this check the boundary erodes silently: flavor and main sources compile together, so an
import of a flavor class only breaks much later, on the other flavor's first build.

Two rules, both enforced on every file under src/main (Kotlin and Java) and on the shared
manifest and resource XML:

  1. No DJI import or fully-qualified class reference (`dji.`, `com.cySdkyc.`). The vendor's
     string meta-data keys (API keys, not classes) are exempt.
  2. No reference to a class that is declared only in a flavor source set. Same-package
     references have no import to catch, so this scans for the simple name of every
     flavor-declared class, with comments and string literals stripped first. In XML the
     same rule applies to `android:name`/`tools:context` values and to fully-qualified
     names, so a flavor activity, fragment or custom view cannot hide in the shared
     manifest or a shared layout.

Usage: python3 scripts/check_main_sdk_free.py [app-module-dir]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

FLAVOR_DIRS = ("src/v5", "src/v4")
MAIN_DIR = "src/main"

DJI_MARKERS = ("dji.", "com.cySdkyc.")

# Vendor strings that are keys, not classes: the shared manifest must declare them, and the
# class-reference scan below must not treat them as SDK coupling.
VENDOR_STRING_KEYS = ("com.dji.sdk.API_KEY", "com.dji.mapkit.maplibre.apikey")

# A class reference in XML: a custom view tag, or an attribute value. The leading character
# class keeps URLs such as `developer.dji.com` out of the match.
DJI_CLASS_MARKER = re.compile(
    r"(?:^|[<\"\s])((?:com\.)?dji\.[A-Za-z0-9_.]+|com\.cySdkyc\.[A-Za-z0-9_.]+)"
)
XML_CLASS_ATTRIBUTE = re.compile(r'(?:android:name|tools:context)\s*=\s*"([^"]*)"')

# Kotlin/Java top-level declarations that can be referenced by name. Functions and
# properties are deliberately not indexed: a same-name local function would produce noise,
# and a missed cross-call still fails loudly on the other flavor's first build.
KOTLIN_DECL = re.compile(
    r"^\s*(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|"
    r"value|annotation|inline|external)\s+)*"
    r"(class|interface|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)
JAVA_DECL = re.compile(
    r"^\s*(?:(?:public|final|abstract|static|protected)\s+)*"
    r"(class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)
PACKAGE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.MULTILINE)


def strip_comments_and_strings(text: str) -> str:
    """Replace comment and string-literal contents with spaces, keeping line numbers.

    Handles Kotlin nested block comments and Java/Kotlin string literals (including triple-
    quoted templates) so that a class name mentioned in prose or in a message string does
    not count as a reference.
    """
    out: list[str] = []
    i, n = 0, len(text)
    state = "code"  # code | line_comment | block_comment | string | triple
    block_depth = 0
    quote = ""
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line_comment"
                out.append(" ")
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block_comment"
                block_depth = 1
                out.append(" ")
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
                block_depth += 1
                out.append("  ")
                i += 2
                continue
            if ch == "*" and nxt == "/":
                block_depth -= 1
                out.append("  ")
                i += 2
                if block_depth == 0:
                    state = "code"
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
        elif state == "string":
            if ch == "\\":
                out.append("  ")
                i += 2
                continue
            if ch == quote:
                state = "code"
            out.append("\n" if ch == "\n" else " ")
            i += 1
        elif state == "triple":
            if text[i : i + 3] == '"""':
                state = "code"
                out.append(" " * 3)
                i += 3
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
    return "".join(out)


def source_files(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*") if p.suffix in (".kt", ".java"))


def declared_classes(root: Path) -> dict[str, tuple[str, str]]:
    """simple name -> (package, 'file:line') for every top-level class-like declaration."""
    found: dict[str, tuple[str, str]] = {}
    for path in source_files(root):
        text = strip_comments_and_strings(path.read_text(encoding="utf-8"))
        package_match = PACKAGE.search(text)
        package = package_match.group(1) if package_match else ""
        pattern = KOTLIN_DECL if path.suffix == ".kt" else JAVA_DECL
        for match in pattern.finditer(text):
            name = match.group(2)
            line = text[: match.start()].count("\n") + 1
            found.setdefault(name, (package, f"{path}:{line}"))
    return found


def resource_files(app_dir: Path) -> list[Path]:
    """The shared manifest and resource XML, which name classes as definitely as code does."""
    files: list[Path] = []
    manifest = app_dir / MAIN_DIR / "AndroidManifest.xml"
    if manifest.is_file():
        files.append(manifest)
    res_root = app_dir / MAIN_DIR / "res"
    if res_root.is_dir():
        files.extend(sorted(res_root.rglob("*.xml")))
    return files


def strip_xml_comments(text: str) -> str:
    """Replace XML comment contents with spaces so line numbers survive."""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("<!--", i):
            end = text.find("-->", i + 4)
            end = n if end == -1 else end + 3
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:end]))
            i = end
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def xml_violations(path: Path, repo_root: Path, foreign: dict[str, tuple[str, str]]) -> list[str]:
    """Class and DJI references in one shared XML file (manifest or resource)."""
    text = strip_xml_comments(path.read_text(encoding="utf-8"))
    for key in VENDOR_STRING_KEYS:
        text = text.replace(key, " " * len(key))
    rel = path.relative_to(repo_root)
    found: dict[tuple[int, str], str] = {}

    for match in DJI_CLASS_MARKER.finditer(text):
        line = text[: match.start()].count("\n") + 1
        found.setdefault(
            (line, match.group(1)),
            f"{rel}:{line}: DJI class reference: {match.group(1)}",
        )

    for name, (package, where) in foreign.items():
        fqn = f"{package}.{name}"
        index = text.find(fqn)
        if index != -1:
            line = text[:index].count("\n") + 1
            found.setdefault(
                (line, fqn),
                f"{rel}:{line}: references flavor-only class {fqn} (declared at {where})",
            )

    for match in XML_CLASS_ATTRIBUTE.finditer(text):
        value = match.group(1).strip()
        if not value:
            continue
        simple = value.rsplit(".", 1)[-1]
        if simple in foreign:
            line = text[: match.start()].count("\n") + 1
            where = foreign[simple][1]
            found.setdefault(
                (line, simple),
                f"{rel}:{line}: android:name/tools:context names flavor-only class "
                f"{simple} (declared at {where})",
            )

    return list(found.values())


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    app_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else repo_root / "LyrebirdApp/lyrebird-app"
    main_root = app_dir / MAIN_DIR
    if not main_root.is_dir():
        print(f"error: no main source set at {main_root}")
        return 2

    violations: list[str] = []

    flavor_names: dict[str, tuple[str, str]] = {}
    for flavor in FLAVOR_DIRS:
        flavor_root = app_dir / flavor
        if flavor_root.is_dir():
            for name, where in declared_classes(flavor_root).items():
                flavor_names.setdefault(name, (where[0], f"[{flavor}] {where[1]}"))

    main_names = set(declared_classes(main_root))
    # A name declared in both sets is the shared-side winner under Kotlin's resolution rules
    # for same-package use; do not flag pure coincidences.
    foreign = {name: where for name, where in flavor_names.items() if name not in main_names}

    name_pattern = re.compile(
        r"\b(" + "|".join(re.escape(n) for n in sorted(foreign, key=len, reverse=True)) + r")\b"
    )
    import_pattern = re.compile(r"^\s*import\s+([A-Za-z0-9_.]+)", re.MULTILINE)

    for path in source_files(main_root):
        rel = path.relative_to(repo_root)
        raw = path.read_text(encoding="utf-8")
        cleaned = strip_comments_and_strings(raw)
        package_match = PACKAGE.search(cleaned)
        file_package = package_match.group(1) if package_match else ""

        # Explicit imports decide what a simple name means inside this file: an import of
        # android.graphics.Point shadows a flavor class that happens to share the name.
        imported: dict[str, str] = {}
        for import_match in import_pattern.finditer(cleaned):
            fqn = import_match.group(1)
            if not fqn.endswith("."):
                imported[fqn.rsplit(".", 1)[-1]] = fqn

        raw_lines = raw.splitlines()
        cleaned_lines = cleaned.splitlines()
        for lineno, code_line in enumerate(cleaned_lines, start=1):
            for marker in DJI_MARKERS:
                if marker in code_line:
                    violations.append(
                        f"{rel}:{lineno}: DJI-coupled reference: {raw_lines[lineno - 1].strip()}"
                    )

        for match in name_pattern.finditer(cleaned):
            name = match.group(1)
            target_package, where = foreign[name]
            source = imported.get(name)
            if source is not None:
                check = source == f"{target_package}.{name}"
            else:
                # Without an import the name can only be the flavor class if it lives in the
                # same package (fully-qualified uses are caught below regardless).
                check = file_package == target_package
            if not check:
                continue
            line = cleaned[: match.start()].count("\n") + 1
            source_line = raw_lines[line - 1].strip()
            violations.append(
                f"{rel}:{line}: references flavor-only class {name} "
                f"(declared at {where}): {source_line}"
            )

        for name, (target_package, where) in foreign.items():
            if f"{target_package}.{name}" in cleaned and name not in imported:
                violations.append(
                    f"{rel}: fully-qualified reference to flavor-only class "
                    f"{target_package}.{name} (declared at {where})"
                )

    for path in resource_files(app_dir):
        violations.extend(xml_violations(path, repo_root, foreign))

    if violations:
        print("main source set is not SDK-neutral:\n")
        for violation in violations:
            print(f"  {violation}")
        print(
            "\nThe shared source set must compile against any adapter. Move the file into the "
            "flavor source set or introduce a neutral port in src/main."
        )
        return 1

    print(
        f"OK: {len(source_files(main_root))} main source files and "
        f"{len(resource_files(app_dir))} shared resource files reference no DJI and no "
        f"flavor-only classes ({len(foreign)} flavor names checked)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
