"""Exercise MAVLink FTP against a live Lyrebird aircraft.

Lists the SD card over FILE_TRANSFER_PROTOCOL, reads the first page of a file end-to-end, and
downloads a file in full only when it is small. MAVLink FTP moves 239 bytes per request-reply
round trip, so transferring multi-megabyte media over it is minutes of stop-and-wait; the
exercise honours that design and points at HTTP for large files. The listing is cross-checked
against the HTTP ``/send/listMedia`` surface (when reachable on port 8080).

Usage:
    python ftp_exercise.py PHONE_IP [KEY_HEX]

KEY_HEX signs every outbound frame (the FTP path itself does not require a signature, but the
same plumbing as the command channel is exercised); it defaults to ``LB_MAVLINK_SIGNING_KEY``.
"""

import os
import sys

import requests
from lyrebird_groundstation.ftp_client import FTP_DATA_BYTES, MavlinkFtpClient

HTTP_PORT = 8080

#: FTP is for small transfers; anything at or under this is downloaded in full as a proof.
SMALL_FILE_LIMIT = 64 * 1024


def _http_listing(host: str) -> list[dict] | None:
    """The HTTP media listing, or None when the HTTP server is unreachable."""
    try:
        response = requests.post(f"http://{host}:{HTTP_PORT}/send/listMedia", timeout=10)
        response.raise_for_status()
        data = response.json()
    except (requests.RequestException, ValueError):
        return None
    files = data.get("files") if isinstance(data, dict) else None
    return files if isinstance(files, list) else None


def _print_listing(entries: list[tuple[str, int]]) -> None:
    """Print the FTP listing, trimmed to the first few entries."""
    print(f"   {len(entries)} file(s) over FTP")
    for name, size in entries[:5]:
        print(f"     {name}  {size} bytes")
    if len(entries) > 5:
        print(f"     ... and {len(entries) - 5} more")


def _open_and_read_first_page(ftp: MavlinkFtpClient, name: str, size: int) -> None:
    """Open [name], read one page end-to-end, and close the session."""
    print()
    print(f"2) Open the smallest file and read its first page: {name} ({size} bytes) ...")
    print("   (the aircraft downloads the file to memory before it can answer the open)")
    session, reported = ftp.open_file_ro(name)
    head = ftp.read_file(session, FTP_DATA_BYTES, 0)
    ftp.terminate_session(session)
    print(f"   session={session} reported={reported} bytes, read {len(head)} bytes")
    if len(head) > 4 and head[0:2] == b"\xff\xd8":
        print("   first bytes are a JPEG SOI marker")
    if reported == size and len(head) == min(FTP_DATA_BYTES, size):
        print("   sizes agree with the listing")


def _download_if_small(ftp: MavlinkFtpClient, name: str, size: int) -> None:
    """Download [name] in full via FTP only when it is a small file."""
    print()
    print(f"3) Download in full only when the file is small (<= {SMALL_FILE_LIMIT} bytes) ...")
    if size > SMALL_FILE_LIMIT:
        print(
            f"   {name} is {size} bytes — too large for FTP's 239-byte round trips; "
            "use HTTP /send/downloadMediaByName for media on the same Wi-Fi"
        )
        return
    body = ftp.download(name)
    print(f"   downloaded {name} fully: {len(body)} bytes")
    if len(body) == size:
        print("   size matches the listing")
    else:
        print(f"   MISMATCH: listing says {size}, downloaded {len(body)}")


def _http_crosscheck(host: str, entries: list[tuple[str, int]]) -> None:
    """Compare FTP listing sizes against the HTTP media listing when reachable."""
    print()
    print("4) Cross-check against HTTP /send/listMedia ...")
    http_files = _http_listing(host)
    if http_files is None:
        print("   HTTP server unreachable; skipping the cross-check")
        return
    by_name = {f["name"]: f for f in http_files if isinstance(f, dict) and "name" in f}
    matches = sum(1 for name, size in entries if by_name.get(name, {}).get("size") == size)
    print(f"   HTTP lists {len(http_files)} file(s); {matches}/{len(entries)} FTP sizes agree")


def main() -> None:
    if len(sys.argv) < 2:
        print("usage: python ftp_exercise.py PHONE_IP [KEY_HEX]")
        sys.exit(2)
    host = sys.argv[1]
    key_hex = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("LB_MAVLINK_SIGNING_KEY", "")
    key = bytes.fromhex(key_hex) if key_hex else None

    with MavlinkFtpClient(host, signing_key=key) as ftp:
        print(f"phone={host} signing={'on' if key else 'off'}")
        print()

        print("1) List the card via MAVLink FTP ...")
        entries = ftp.list_directory()
        _print_listing(entries)
        if not entries:
            print("   (nothing on the card)")
            return

        name, size = min(entries, key=lambda entry: entry[1])
        _open_and_read_first_page(ftp, name, size)
        _download_if_small(ftp, name, size)
        _http_crosscheck(host, entries)

    print()
    print("MAVLink FTP exercise complete.")


if __name__ == "__main__":
    main()
