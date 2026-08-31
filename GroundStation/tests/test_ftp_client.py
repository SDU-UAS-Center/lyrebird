"""MAVLink FTP client: request framing, reply parsing, listing paging, chunked download."""

import struct
import time

import pytest
from pymavlink.dialects.v20 import common as mavlink_common
from lyrebird_groundstation.ftp_client import (
    AIRCRAFT_COMPONENT_ID,
    AIRCRAFT_SYSTEM_ID,
    ERR_EOF,
    ERR_FILE_NOT_FOUND,
    FTP_HEADER_BYTES,
    FTP_PAYLOAD_BYTES,
    OP_ACK,
    OP_LIST_DIRECTORY,
    OP_NAK,
    OP_OPEN_FILE_RO,
    OP_READ_FILE,
    OP_RESET_SESSIONS,
    OP_TERMINATE_SESSION,
    FtpError,
    FtpTimeoutError,
    MavlinkFtpClient,
    _parse_reply,
    _request_payload,
)


class _Sink:
    def __init__(self):
        self.buf = b""

    def write(self, data):
        self.buf += data


def _frame_ftp(payload):
    """A FILE_TRANSFER_PROTOCOL frame as the aircraft would send it (sysid 127, compid 1)."""
    sink = _Sink()
    mavlink_common.MAVLink(
        sink, srcSystem=AIRCRAFT_SYSTEM_ID, srcComponent=AIRCRAFT_COMPONENT_ID
    ).file_transfer_protocol_send(0, 255, 190, payload)
    return sink.buf


def _ftp_ack(seq, session, req_opcode, size, offset, data=b""):
    payload = bytearray(FTP_PAYLOAD_BYTES)
    struct.pack_into("<HBBBBBBI", payload, 0, seq, session, OP_ACK, size, req_opcode, 0, 0, offset)
    payload[FTP_HEADER_BYTES : FTP_HEADER_BYTES + len(data)] = data
    return _frame_ftp(payload)


def _ftp_nak(seq, session, req_opcode, error):
    payload = bytearray(FTP_PAYLOAD_BYTES)
    struct.pack_into("<HBBBBBBI", payload, 0, seq, session, OP_NAK, 1, req_opcode, 0, 0, 0)
    payload[FTP_HEADER_BYTES] = error
    return _frame_ftp(payload)


def _listing_page(seq, offset, names):
    data = b"".join(f"F{name}\t{len(name)}\x00".encode("ascii") for name in names)
    return _ftp_ack(seq, 0, OP_LIST_DIRECTORY, len(data), offset, data)


class _FakeSocket:
    """Hands back queued frames, records what was sent, then blocks like an idle socket."""

    def __init__(self, frames):
        self._frames = list(frames)
        self.sent = []

    def sendto(self, data, address):
        self.sent.append((data, address))
        return len(data)

    def settimeout(self, timeout):
        return None

    def recvfrom(self, size):
        if not self._frames:
            time.sleep(0.02)
            raise TimeoutError
        return self._frames.pop(0), ("192.168.50.127", 14550)

    def close(self):
        return None


def _client(frames, **kwargs):
    kwargs.setdefault("timeout", 1.0)
    client = MavlinkFtpClient("192.168.50.127", **kwargs)
    client._socket = _FakeSocket(frames)
    return client


def _decode(frame):
    parser = mavlink_common.MAVLink(None)
    parser.robust_parsing = True
    return (parser.parse_buffer(frame) or [None])[0]


def _sent_payloads(client):
    return [_decode(data).payload for data, _ in client._socket.sent]


def _opcode(payload):
    # FTP header: seq(u16) session(u8) opcode(u8) size(u8) ...
    return payload[3]


# -- wire format -----------------------------------------------------------------------------


def test_a_request_payload_matches_the_aircraft_wire_format():
    payload = _request_payload(0x1234, 7, OP_READ_FILE, 64, 4096, b"DJI_0001.JPG")
    assert len(payload) == FTP_PAYLOAD_BYTES
    seq, session, opcode, size, req_opcode, burst, padding, offset = struct.unpack_from(
        "<HBBBBBBI", payload, 0
    )
    assert (seq, session, opcode, size, req_opcode, burst, padding, offset) == (
        0x1234,
        7,
        OP_READ_FILE,
        64,
        0,
        0,
        0,
        4096,
    )
    assert bytes(payload[FTP_HEADER_BYTES:].split(b"\x00")[0]) == b"DJI_0001.JPG"


def test_an_ack_reply_parses_from_an_aircraft_frame():
    frame = _ftp_ack(0x1234, 3, OP_OPEN_FILE_RO, 4, 0, (100).to_bytes(4, "little"))
    reply = _parse_reply(bytes(_decode(frame).payload))
    assert reply.seq == 0x1234
    assert reply.session == 3
    assert reply.opcode == OP_ACK
    assert reply.size == 4
    assert reply.req_opcode == OP_OPEN_FILE_RO
    assert int.from_bytes(reply.data[:4], "little") == 100


def test_sent_frames_target_the_aircraft_and_claim_a_gcs_identity():
    client = _client([_ftp_ack(1, 0, OP_RESET_SESSIONS, 0, 0)])
    client.reset_sessions()
    msg = _decode(client._socket.sent[0][0])
    assert msg.get_type() == "FILE_TRANSFER_PROTOCOL"
    assert msg.target_system == AIRCRAFT_SYSTEM_ID
    assert msg.target_component == AIRCRAFT_COMPONENT_ID
    assert msg.get_srcSystem() == 255
    assert msg.get_srcComponent() == 190
    assert _opcode(bytes(msg.payload)) == OP_RESET_SESSIONS
    client.close()


# -- listing ---------------------------------------------------------------------------------


def test_list_directory_pages_by_offset_until_eof():
    frames = [
        _listing_page(1, 0, ["A.JPG", "B.JPG"]),
        _listing_page(2, 2, ["C.JPG", "D.JPG"]),
        _listing_page(3, 4, ["E.JPG"]),
        _ftp_nak(4, 0, OP_LIST_DIRECTORY, ERR_EOF),
    ]
    client = _client(frames)
    entries = client.list_directory()
    assert entries == [
        ("A.JPG", 5),
        ("B.JPG", 5),
        ("C.JPG", 5),
        ("D.JPG", 5),
        ("E.JPG", 5),
    ]
    client.close()


def test_list_directory_raises_on_a_real_error_not_eof():
    client = _client([_ftp_nak(1, 0, OP_LIST_DIRECTORY, ERR_FILE_NOT_FOUND)])
    with pytest.raises(FtpError) as exc:
        client.list_directory()
    assert exc.value.code == ERR_FILE_NOT_FOUND
    client.close()


# -- download --------------------------------------------------------------------------------


def test_download_streams_chunks_and_terminates_the_session():
    body = bytes(range(256)) * 2  # 512 bytes
    frames = [
        _ftp_ack(1, 9, OP_OPEN_FILE_RO, 4, 0, (len(body)).to_bytes(4, "little")),
        _ftp_ack(2, 9, OP_READ_FILE, 239, 0, body[0:239]),
        _ftp_ack(3, 9, OP_READ_FILE, 239, 239, body[239:478]),
        _ftp_ack(4, 9, OP_READ_FILE, 34, 478, body[478:512]),
        _ftp_ack(5, 9, OP_TERMINATE_SESSION, 0, 0),
    ]
    client = _client(frames)
    assert client.download("DJI_0001.JPG") == body
    client.close()

    opcodes = [_opcode(bytes(p)) for p in _sent_payloads(client)]
    assert opcodes == [
        OP_OPEN_FILE_RO,
        OP_READ_FILE,
        OP_READ_FILE,
        OP_READ_FILE,
        OP_TERMINATE_SESSION,
    ]
    # The reads ask for exactly what is left, never more than a 239-byte page.
    read_sizes = [bytes(p)[4] for p in _sent_payloads(client) if _opcode(bytes(p)) == OP_READ_FILE]
    assert read_sizes == [239, 239, 34]


def test_download_reuses_a_page_when_the_server_returns_a_short_chunk():
    body = bytes(i % 256 for i in range(300))
    frames = [
        _ftp_ack(1, 5, OP_OPEN_FILE_RO, 4, 0, (len(body)).to_bytes(4, "little")),
        _ftp_ack(2, 5, OP_READ_FILE, 100, 0, body[0:100]),
        _ftp_ack(3, 5, OP_READ_FILE, 200, 100, body[100:300]),
        _ftp_ack(4, 5, OP_TERMINATE_SESSION, 0, 0),
    ]
    client = _client(frames)
    assert client.download("SHORT.JPG") == body
    client.close()
    # The client asks for a full page, gets 100 back, and re-requests only what is left.
    read_sizes = [bytes(p)[4] for p in _sent_payloads(client) if _opcode(bytes(p)) == OP_READ_FILE]
    assert read_sizes == [239, 200]


def test_open_missing_file_raises_with_the_nak_code():
    client = _client([_ftp_nak(1, 0, OP_OPEN_FILE_RO, ERR_FILE_NOT_FOUND)])
    with pytest.raises(FtpError) as exc:
        client.open_file_ro("NOPE.JPG")
    assert exc.value.code == ERR_FILE_NOT_FOUND
    client.close()


# -- reliability -----------------------------------------------------------------------------


def test_a_stale_reply_does_not_satisfy_a_newer_request():
    # A reply for a request that was never acknowledged sits in the inbox; the client waits for
    # the seq it actually sent rather than grabbing the first FTP reply that arrives.
    client = _client(
        [_ftp_ack(99, 0, OP_RESET_SESSIONS, 0, 0), _ftp_ack(1, 0, OP_RESET_SESSIONS, 0, 0)]
    )
    client.reset_sessions()
    client.close()


def test_no_reply_within_the_deadline_raises_ftp_timeout():
    client = _client([], timeout=0.15)
    with pytest.raises(FtpTimeoutError):
        client.reset_sessions()
    client.close()
