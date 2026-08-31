"""Backward-compatibility import path for the Lyrebird Safety Computer client.

The real implementation lives in lyrebird_groundstation.safety. This module only
exists so historical import paths (`from djiInterfaceSafety import DJIInterfaceSafety`)
keep working; new code should import from lyrebird_groundstation.safety.
"""

from lyrebird_groundstation.safety import (
    EP_RELEASE_SAFETY_CONTROL,
    SAFETY_TOKEN,
    SAFETY_TOKEN_HEADER,
    DJIInterfaceSafety,
)

__all__ = [
    "EP_RELEASE_SAFETY_CONTROL",
    "SAFETY_TOKEN",
    "SAFETY_TOKEN_HEADER",
    "DJIInterfaceSafety",
]
