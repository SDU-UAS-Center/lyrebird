"""Exercise MAVLink 2 signing against a live Lyrebird aircraft.

The phone must already have ``lb_mav_0_signing_key`` and ``lb_mav_0_allow_flight``
configured (this script sets nothing on the phone). Three commands prove the signing
gate, all of them MAV_CMD_NAV_LAND — inert on the ground, but a flight command, so they
cross the authority gate where a payload command would not:

    1. an UNSIGNED command -> accepted as the Pilot (authority stays PILOT)
    2. a SIGNED command    -> accepted as the Safety Computer (authority latches to SAFETY)
    3. an UNSIGNED command -> refused, because the Pilot is now locked out
    4. a SIGNED release    -> accepted: /releaseSafetyControl returns authority to the Pilot
    5. an UNSIGNED release -> refused: the Pilot cannot release safety

Usage:
    python signing_exercise.py PHONE_IP [KEY_HEX]

KEY_HEX defaults to ``LB_MAVLINK_SIGNING_KEY`` or the 00..1f test key shared with the
aircraft's MavlinkSigningTest.
"""

import os
import sys

from lyrebird_groundstation.transport import (
    MavlinkCommandChannel,
)

DEFAULT_KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"


def _one_command(host: str, endpoint: str, key: bytes | None) -> str:
    """Send one command on a fresh channel, so leftover acks cannot leak between steps.

    The aircraft acknowledges a motion command more than once (accepted, then finished), and a
    channel reuses one inbox — so reusing a channel would let a step read the previous step's
    stale ack. A fresh channel per command keeps each reply honest.
    """
    return MavlinkCommandChannel(host, signing_key=key).send(endpoint, "", timeout=5)


def main() -> None:
    if len(sys.argv) < 2:
        print("usage: python signing_exercise.py PHONE_IP [KEY_HEX]")
        sys.exit(2)
    host = sys.argv[1]
    key_hex = (
        sys.argv[2]
        if len(sys.argv) > 2
        else os.environ.get("LB_MAVLINK_SIGNING_KEY", DEFAULT_KEY_HEX)
    )
    key = bytes.fromhex(key_hex)

    print(f"phone={host} key={key_hex[:8]}...")
    print()

    print("1) UNSIGNED land         -> expect accepted as PILOT (authority stays PILOT)")
    print("   ", _one_command(host, "/send/land", None))
    print()
    print("2) SIGNED land           -> expect accepted as SAFETY (authority latches to SAFETY)")
    print("   ", _one_command(host, "/send/land", key))
    print()
    print("3) UNSIGNED land         -> expect REFUSED (Pilot is locked out once SAFETY holds)")
    print("   ", _one_command(host, "/send/land", None))
    print()
    print("4) SIGNED safety release -> expect accepted (authority returns to PILOT)")
    print("   ", _one_command(host, "/releaseSafetyControl", key))
    print()
    print("5) UNSIGNED safety rel.  -> expect REFUSED (the Pilot cannot release safety)")
    print("   ", _one_command(host, "/releaseSafetyControl", None))
    print()
    print("After step 2 the phone's UI should show 'SAFETY COMPUTER IN CONTROL';")
    print("after step 4 it should clear again.")


if __name__ == "__main__":
    main()
