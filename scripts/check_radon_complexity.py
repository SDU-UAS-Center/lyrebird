"""Fail when Radon reports C-or-worse cyclomatic complexity blocks."""

from __future__ import annotations

import re
import subprocess
import sys
from collections import Counter

TARGET_PATHS = ("GroundStation",)
BAD_GRADE_PATTERN = re.compile(r" - [C-F] \(")
GRADE_PATTERN = re.compile(r" - ([A-F]) \(")


def main() -> int:
    result = subprocess.run(
        [sys.executable, "-m", "radon", "cc", "-s", *TARGET_PATHS],
        check=False,
        capture_output=True,
        text=True,
    )

    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        return result.returncode

    report = result.stdout
    counts = Counter(GRADE_PATTERN.findall(report))

    print("Cyclomatic complexity grade counts:")
    for grade in "ABCDEF":
        print(f"  {grade}: {counts.get(grade, 0)}")

    bad_lines = [line for line in report.splitlines() if BAD_GRADE_PATTERN.search(line)]
    if bad_lines:
        print("\nC-or-worse complexity blocks found:")
        print("\n".join(bad_lines))
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
