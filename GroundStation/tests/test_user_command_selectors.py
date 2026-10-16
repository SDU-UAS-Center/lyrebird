"""Keep MAV_CMD_USER_1 selectors unique and aligned across the dialect and clients."""

import re
import xml.etree.ElementTree as ET
from pathlib import Path

from lyrebird_groundstation import transport


ROOT = Path(__file__).resolve().parents[2]


def _dialect_user1_selectors() -> dict[str, float]:
    dialect = ET.parse(ROOT / "GroundStation/mavlink/lyrebird.xml")
    selector_enum = dialect.find(".//enum[@name='LYREBIRD_USER_1_SELECTOR']")
    assert selector_enum is not None
    return {
        entry.attrib["name"].removeprefix("LYREBIRD_USER_1_"): float(entry.attrib["value"])
        for entry in selector_enum.findall("entry")
    }


def _kotlin_user1_selectors() -> dict[str, float]:
    source = (
        ROOT
        / "LyrebirdApp/lyrebird-core/src/main/java/com/lyrebird/rc/mavlink/MavlinkProtocol.kt"
    ).read_text()
    return {
        name.removeprefix("USER1_"): float(value)
        for name, value in re.findall(r"const val (USER1_[A-Z_]+) = ([\d.]+)f", source)
    }


def _assert_unique(selectors: dict[str, float]) -> None:
    values = list(selectors.values())
    assert len(values) == len(set(values)), f"duplicate selector values in {selectors}"


def test_user1_selectors_are_unique_and_match_dialect_and_clients():
    dialect = _dialect_user1_selectors()
    python = {
        name.removeprefix("USER1_"): value
        for name, value in vars(transport).items()
        if name.startswith("USER1_")
    }
    kotlin = _kotlin_user1_selectors()

    _assert_unique(dialect)
    _assert_unique(python)
    _assert_unique(kotlin)
    assert python == dialect
    assert kotlin == dialect
