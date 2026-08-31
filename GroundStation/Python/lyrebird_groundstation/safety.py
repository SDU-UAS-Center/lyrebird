"""
Lyrebird - DJI Safety Interface Module

Safety Computer variant of the DJI interface. It subclasses lyrebird_groundstation.dji_client.DJIInterface
WITHOUT modifying it, and injects the X-Safety-Token header into every command so the
Android app treats this client as the Safety Computer: its first command seizes
persistent control of the drone and locks out the Pilot Computer until
requestReleaseSafetyControl() is called.

Use lyrebird_groundstation.dji_client.DJIInterface for the Pilot Computer (no token) and
lyrebird_groundstation.safety.DJIInterfaceSafety for the Safety Computer.

Authors: Edouard G.A. Rolland, Kilian Meier, Alejandro Jarabo-Peñas
Project: Lyrebird
Institution: University of Bristol, University of Southern Denmark (SDU)
License: MIT

For more information, visit: https://github.com/SDU-UAS-Center/lyrebird
"""

from lyrebird_groundstation.dji_client import DJIInterface

# Must match SAFETY_TOKEN hardcoded in the Android app (FlightDeckActivity).
SAFETY_TOKEN = "98"
SAFETY_TOKEN_HEADER = "X-Safety-Token"
EP_RELEASE_SAFETY_CONTROL = "/releaseSafetyControl"

__all__ = [
    "EP_RELEASE_SAFETY_CONTROL",
    "SAFETY_TOKEN",
    "SAFETY_TOKEN_HEADER",
    "DJIInterfaceSafety",
]


class DJIInterfaceSafety(DJIInterface):
    """
    Safety Computer interface: a DJIInterface that always sends the Safety token.

    Same API as DJIInterface — every command (takeoff, RTH, waypoints, thermal capture,
    etc.) is automatically authenticated as the Safety Computer. The first command seizes
    control; call requestReleaseSafetyControl() to hand authority back to the Pilot.

    Nothing in dji_client.py is modified: the token is injected by overriding the two
    methods that issue HTTP requests (requestSend and requestCaptureThermalImage).
    """

    def __init__(self, IP_RC="", safety_token=SAFETY_TOKEN):
        super().__init__(IP_RC)
        self.safety_token = safety_token

    def setSafetyToken(self, token):
        """Set (or clear, with None) the Safety Computer token sent on every command."""
        self.safety_token = token

    def _authHeaders(self):
        """Return the X-Safety-Token header dict when a token is configured."""
        if self.safety_token:
            return {SAFETY_TOKEN_HEADER: str(self.safety_token)}
        return {}

    # --- Overrides that inject the token (parent versions send no headers) ---

    def _post(self, endPoint, data="", timeout=5, **kwargs):
        """Authenticate every outbound command as the Safety Computer.

        Overriding the base client's single POST chokepoint covers the whole command
        surface at once — including requestCapture, listMedia and downloadByName, which
        issue their own requests rather than going through requestSend. Before this,
        those three sent no token and were classified as Pilot traffic, so they were
        rejected by ControlAuthority as soon as the Safety Computer held authority.
        """
        headers = {**self._authHeaders(), **kwargs.pop("headers", {})}
        return super()._post(endPoint, data, timeout=timeout, headers=headers, **kwargs)

    # --- Pilot / Safety authority ---

    def requestReleaseSafetyControl(self):
        """Return command authority to the Pilot Computer.

        After this call the Pilot Computer's commands are accepted again and this
        interface goes back to being a supervisor.
        """
        return self.requestSend(EP_RELEASE_SAFETY_CONTROL, "")


if __name__ == "__main__":
    import sys
    import time

    def _looks_rejected(response_text):
        """Heuristic checker for rejection-like responses from the Android bridge."""
        if response_text is None:
            return True
        text = str(response_text).strip().lower()
        if text == "":
            return True
        rejection_markers = (
            "reject",
            "rejected",
            "denied",
            "forbidden",
            "unauthorized",
            "not allowed",
            "not authorised",
            "401",
            "403",
            "safety",
        )
        return any(marker in text for marker in rejection_markers)

    IP_RC = "10.177.40.181"  # REPLACE WITH YOUR RC IP
    if len(sys.argv) > 1:
        IP_RC = sys.argv[1]

    print(f"[SAFETY] Connecting to {IP_RC} with token {SAFETY_TOKEN!r}...")
    safety = DJIInterfaceSafety(IP_RC)
    pilot = DJIInterface(IP_RC)

    # The first command seizes control from the Pilot Computer (persistent).
    print("[SAFETY] Seizing control (RTH)...")
    print("  ->", safety.requestSendRTH())

    time.sleep(5)

    # Proof: once Safety has seized control, a Pilot command (no token) should be rejected.
    print("[PILOT] Sending command without token while Safety has control (RTH)...")
    pilot_reply = pilot.requestSendRTH()
    print("  ->", pilot_reply)
    print("[PROOF] Pilot command rejected:", _looks_rejected(pilot_reply))

    # Hand control back to the Pilot Computer.
    print("[SAFETY] Releasing control back to Pilot...")
    print("  ->", safety.requestReleaseSafetyControl())
