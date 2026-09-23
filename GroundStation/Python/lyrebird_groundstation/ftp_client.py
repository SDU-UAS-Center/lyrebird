"""MAVLink FTP v1 client for Lyrebird.

Speaks the read-only subset the aircraft serves — list the SD card, open a file for reading,
read it in chunks, terminate sessions — over FILE_TRANSFER_PROTOCOL (message id 110). The wire
format mirrors the aircraft's ``MavlinkFtp`` encoding; see https://mavlink.io/en/services/ftp.html.

pymavlink already splits the FILE_TRANSFER_PROTOCOL payload into the three target fields plus the
251-byte FTP payload, so the request builder only ever touches the 251-byte FTP payload and the
reply parser reads it back the same way.

A client that signs its frames (``signing_key=``) is read by the aircraft as the Safety Computer,
exactly like the command channel; the signing bits are reused from ``transport``.
"""

from __future__ import annotations

import contextlib
import socket
import struct
import threading
import time
from dataclasses import dataclass
from typing import Any

from lyrebird_groundstation.transport import (
    DEFAULT_MAVLINK_PORT,
    _FrameSigner,
    _FrameSink,
)

#: The 251-byte FTP payload pymavlink carries after the three target fields.
FTP_PAYLOAD_BYTES = 251
FTP_HEADER_BYTES = 12
FTP_DATA_BYTES = 239

# Client opcodes (the read-only subset the aircraft implements).
OP_NONE = 0
OP_TERMINATE_SESSION = 1
OP_RESET_SESSIONS = 2
OP_LIST_DIRECTORY = 3
OP_OPEN_FILE_RO = 4
OP_READ_FILE = 5

# Response opcodes.
OP_ACK = 128
OP_NAK = 129

# MAV_FTP_ERR values.
ERR_FAIL = 1
ERR_INVALID_DATA_SIZE = 3
ERR_INVALID_SESSION = 4
ERR_NO_SESSIONS = 5
ERR_EOF = 6
ERR_UNKNOWN_COMMAND = 7
ERR_FILE_NOT_FOUND = 10

#: The aircraft answers FTP from its MAVLink endpoint: system id 127, autopilot component 1.
AIRCRAFT_SYSTEM_ID = 127
AIRCRAFT_COMPONENT_ID = 1

#: How long to keep resending a request before giving up, and how often to resend.
DEFAULT_TIMEOUT = 5.0
RESEND_INTERVAL = 0.5

#: Opening a file makes the aircraft download the whole file from the camera into memory before
#: it can reply, so the open deadline is generous and the request is sent exactly once — resending
#: an in-flight open would start a second full download.
OPEN_DEFAULT_TIMEOUT = 180.0

#: The aircraft refreshes the SD-card list from the camera when asked, and a cold camera can take
#: tens of seconds to answer; listing therefore uses a generous default deadline.
LIST_DEFAULT_TIMEOUT = 30.0

#: Most recent replies kept while nothing is waiting for them.
_INBOX_LIMIT = 64


class FtpError(Exception):
    """A NAK carrying a MAV_FTP_ERR code; [code] is one of the ERR_ constants."""

    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code


class FtpTimeoutError(Exception):
    """No reply matched the request within the deadline."""


@dataclass
class FtpReply:
    """A decoded ACK or NAK payload."""

    seq: int
    session: int
    opcode: int
    size: int
    req_opcode: int
    offset: int
    data: bytes

    @property
    def error(self) -> int:
        """The MAV_FTP_ERR code when this is a NAK, else -1."""
        return self.data[0] if self.opcode == OP_NAK else -1


def _parse_reply(payload: bytes) -> FtpReply | None:
    """Decode a 251-byte FTP payload into a reply, or None when it is malformed."""
    if len(payload) < FTP_HEADER_BYTES:
        return None
    seq, session, opcode, size, req_opcode, _burst, _padding, offset = struct.unpack_from(
        "<HBBBBBBI", payload, 0
    )
    return FtpReply(
        seq=seq,
        session=session,
        opcode=opcode,
        size=size,
        req_opcode=req_opcode,
        offset=offset,
        data=payload[FTP_HEADER_BYTES:],
    )


def _request_payload(
    seq: int,
    session: int,
    opcode: int,
    size: int,
    offset: int,
    data: bytes = b"",
) -> bytes:
    """Encode a 251-byte FTP request payload (see MavlinkFtp on the aircraft)."""
    payload = bytearray(FTP_PAYLOAD_BYTES)
    struct.pack_into("<HBBBBBBI", payload, 0, seq, session, opcode, size, 0, 0, 0, offset)
    if data:
        payload[FTP_HEADER_BYTES : FTP_HEADER_BYTES + len(data)] = data[:FTP_DATA_BYTES]
    return bytes(payload)


def _parse_listing(data: bytes) -> list[tuple[str, int]]:
    """Decode a ListDirectory page: ``F<name>\\t<size>\\0`` entries, NUL-separated."""
    entries: list[tuple[str, int]] = []
    for entry in data.split(b"\x00"):
        if not entry or entry[:1] != b"F":
            continue
        name, sep, size = entry[1:].partition(b"\t")
        if not sep or not name or not size.isdigit():
            continue
        entries.append((name.decode("ascii", "replace"), int(size)))
    return entries


class MavlinkFtpClient:
    """A MAVLink FTP v1 client for the Lyrebird aircraft's read-only server."""

    def __init__(
        self,
        host: str,
        port: int = DEFAULT_MAVLINK_PORT,
        target_system: int = AIRCRAFT_SYSTEM_ID,
        target_component: int = AIRCRAFT_COMPONENT_ID,
        src_system: int = 255,
        src_component: int = 190,
        signing_key: bytes | None = None,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self._target_system = target_system
        self._target_component = target_component
        self._socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._socket.settimeout(1.0)
        self._lock = threading.Lock()
        self._seq = 0
        self._closed = False
        self._mav: Any | None = None
        self._parser: Any | None = None
        self._reader: threading.Thread | None = None
        self._inbox: list[FtpReply] = []
        self._inbox_ready = threading.Condition()
        self._sink = _FrameSink(_FrameSigner(signing_key) if signing_key is not None else None)
        self._src_system = src_system
        self._src_component = src_component

    # -- public API --------------------------------------------------------------------------

    def list_directory(self, timeout: float | None = None) -> list[tuple[str, int]]:
        """Every entry on the card as (name, size), paging until the server says EOF.

        The aircraft refreshes its SD-card list from the camera on the first listing, which a
        cold camera can take tens of seconds over, so the default deadline is generous.
        """
        entries: list[tuple[str, int]] = []
        offset = 0
        while True:
            reply = self._request(
                OP_LIST_DIRECTORY,
                offset=offset,
                timeout=timeout if timeout is not None else LIST_DEFAULT_TIMEOUT,
            )
            if reply.opcode == OP_NAK:
                if reply.error == ERR_EOF:
                    return entries
                raise FtpError(reply.error, f"list directory failed at offset {offset}")
            page = _parse_listing(reply.data[: reply.size])
            entries.extend(page)
            if not page:
                raise FtpError(ERR_FAIL, "empty directory page while listing")
            offset += len(page)

    def open_file_ro(self, name: str, timeout: float | None = None) -> tuple[int, int]:
        """Open [name] for reading; returns (session, file size in bytes).

        The aircraft downloads the whole file before it can ACK, so the open is sent once with a
        long deadline rather than resending a request that is already working.
        """
        encoded = name.encode("ascii", "replace")
        reply = self._request(
            OP_OPEN_FILE_RO,
            size=len(encoded),
            data=encoded,
            timeout=timeout if timeout is not None else OPEN_DEFAULT_TIMEOUT,
            resend=False,
        )
        if reply.opcode == OP_NAK:
            raise FtpError(reply.error, f"cannot open {name!r}")
        return reply.session, int.from_bytes(reply.data[:4], "little")

    def read_file(
        self, session: int, size: int, offset: int, timeout: float | None = None
    ) -> bytes:
        """Read up to [size] bytes at [offset]; returns whatever the aircraft sent."""
        reply = self._request(
            OP_READ_FILE, session=session, size=size, offset=offset, timeout=timeout
        )
        if reply.opcode == OP_NAK:
            raise FtpError(reply.error, f"read failed at offset {offset}")
        return bytes(reply.data[: reply.size])

    def terminate_session(self, session: int, timeout: float | None = None) -> None:
        reply = self._request(OP_TERMINATE_SESSION, session=session, timeout=timeout)
        if reply.opcode == OP_NAK:
            raise FtpError(reply.error, "terminate session failed")

    def reset_sessions(self, timeout: float | None = None) -> None:
        reply = self._request(OP_RESET_SESSIONS, timeout=timeout)
        if reply.opcode == OP_NAK:
            raise FtpError(reply.error, "reset sessions failed")

    def download(self, name: str, timeout: float | None = None) -> bytes:
        """Open [name], stream it in 239-byte chunks, and close the session.

        The session is released even when a read fails, so a half-read file never leaks a
        server-side session.
        """
        session, size = self.open_file_ro(name, timeout=timeout)
        try:
            chunks: list[bytes] = []
            offset = 0
            while offset < size:
                wanted = min(FTP_DATA_BYTES, size - offset)
                chunk = self.read_file(session, wanted, offset, timeout=timeout)
                if not chunk:
                    raise FtpError(ERR_EOF, f"server returned no data at offset {offset}")
                chunks.append(chunk)
                offset += len(chunk)
            return b"".join(chunks)
        finally:
            with contextlib.suppress(FtpError):
                self.terminate_session(session, timeout=timeout)

    def close(self) -> None:
        """Stop the reader and release the socket. Safe to call more than once."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self._socket.close()
        with self._inbox_ready:
            self._inbox_ready.notify_all()
        reader = self._reader
        if reader is not None and reader is not threading.current_thread():
            reader.join(timeout=1.0)

    def __enter__(self) -> MavlinkFtpClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # -- transport ---------------------------------------------------------------------------

    def _next_seq(self) -> int:
        with self._lock:
            self._seq = (self._seq + 1) & 0xFFFF
            return self._seq

    def _request(
        self,
        opcode: int,
        session: int = 0,
        size: int = 0,
        offset: int = 0,
        data: bytes = b"",
        timeout: float | None = None,
        resend: bool = True,
    ) -> FtpReply:
        """Send one request and wait for the reply carrying its seq.

        With [resend] the request is repeated on an interval until the deadline, which is safe
        because the cheap operations (list, read, terminate) are idempotent. An operation that
        starts expensive work on the aircraft — opening a file downloads it — is sent exactly
        once and waited out, so a lost final reply cannot trigger duplicate work.

        Replies are matched on the exact seq, so a stale reply from an earlier timed-out request
        can never satisfy a newer one — the aircraft echoes the request's seq back.
        """
        seq = self._next_seq()
        payload = _request_payload(seq, session, opcode, size, offset, data)
        deadline = time.time() + (timeout if timeout is not None else self.timeout)
        self._start_reader()
        last_send = 0.0
        while True:
            now = time.time()
            if now - last_send >= RESEND_INTERVAL or not resend:
                self._send(payload)
                last_send = now
            if not resend:
                reply = self._await(seq, max(0.0, deadline - time.time()))
                if reply is None:
                    raise FtpTimeoutError(f"no FTP reply for opcode {opcode} seq {seq}")
                return reply
            reply = self._await(seq, min(RESEND_INTERVAL, deadline - now))
            if reply is not None:
                return reply
            if time.time() >= deadline:
                raise FtpTimeoutError(f"no FTP reply for opcode {opcode} seq {seq}")

    def _send(self, payload: bytes) -> None:
        mav = self._ensure_mav()
        self._sink.buf = b""
        mav.file_transfer_protocol_send(0, self._target_system, self._target_component, payload)
        with self._lock:
            if not self._closed:
                self._socket.sendto(self._sink.buf, (self.host, self.port))

    def _await(self, seq: int, seconds: float) -> FtpReply | None:
        deadline = time.time() + max(0.0, seconds)
        with self._inbox_ready:
            while True:
                for index, reply in enumerate(self._inbox):
                    if reply.seq == seq:
                        del self._inbox[index]
                        return reply
                remaining = deadline - time.time()
                if remaining <= 0:
                    return None
                self._inbox_ready.wait(remaining)

    def _start_reader(self) -> None:
        if self._reader is not None:
            return
        with self._lock:
            if self._reader is not None:
                return
            self._reader = threading.Thread(
                target=self._read_loop, name="mavlink-ftp-reader", daemon=True
            )
            self._reader.start()

    def _read_loop(self) -> None:
        parser = self._ensure_parser()
        while not self._closed:
            try:
                data, _ = self._socket.recvfrom(2048)
            except TimeoutError:
                continue
            except OSError:
                return
            for msg in parser.parse_buffer(data) or []:
                if msg.get_type() != "FILE_TRANSFER_PROTOCOL":
                    continue
                reply = _parse_reply(bytes(msg.payload))
                if reply is None:
                    continue
                with self._inbox_ready:
                    self._inbox.append(reply)
                    del self._inbox[:-_INBOX_LIMIT]
                    self._inbox_ready.notify_all()

    def _ensure_mav(self) -> Any:
        from pymavlink.dialects.v20 import common as mavlink_common

        if self._mav is None:
            self._mav = mavlink_common.MAVLink(
                self._sink, srcSystem=self._src_system, srcComponent=self._src_component
            )
        return self._mav

    def _ensure_parser(self) -> Any:
        from pymavlink.dialects.v20 import common as mavlink_common

        if self._parser is None:
            self._parser = mavlink_common.MAVLink(None)
            self._parser.robust_parsing = True
        return self._parser
