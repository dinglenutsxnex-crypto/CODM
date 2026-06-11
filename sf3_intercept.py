"""
sf3_intercept.py  —  Surgical SF3 battle-result patcher
========================================================
Transparent TCP proxy. Sits between the game app and the SF3 server.
Intercepts ONLY event_battle_finish_fight (client→server) and patches
the result field to WIN. Every other packet is forwarded as raw bytes
with zero processing — including pings, so counter sync is never touched.

How it works
────────────
  Game app ──► [proxy :4443] ──► SF3 server :443
              (patch here)

  All packets from server→game are piped raw, untouched.

  For each client→server packet the proxy:
    1. Reads the SF3 frame header (1–5 bytes) to get exact payload size
    2. Reads the payload
    3. Decompresses if needed (0x02 frame)
    4. Peeks at the outer proto command field
    5. If cmd == "event_battle_finish_fight":
         → surgically patches inner fields → re-encodes → sends
    6. Otherwise: sends the ORIGINAL raw bytes (no re-encode overhead)

Patch applied
─────────────
  Keeps from game's packet:
    field[1]  battleID   (whatever battle the game started)
    field[6]  seed/ts    (live timestamp the game generated)

  Replaces / adds:
    field[4]  = 1        WIN   (game sends 3 = LOSS)
    field[5]  = rounds   wonRounds  (game omits this on loss)
    field[7]  = rounds   totalRounds (game sends 1 on loss)
    field[10] = ITEMS    captured from a real win session
    field[13] = STATS    captured from a real win session
    field[14] = LEVEL    28 (confirmed from real win capture)

Setup on Android (Termux + root)
─────────────────────────────────
  # redirect game's outbound port 443 to local proxy
  iptables -t nat -A OUTPUT -p tcp --dport 443 \
    -m owner --uid-owner $(pm list packages -U com.nekki.shadowfight3 | \
      grep -o 'uid:[0-9]*' | cut -d: -f2) \
    -j REDIRECT --to-port 4443

  python3 sf3_intercept.py

  # to remove rule when done:
  iptables -t nat -D OUTPUT -p tcp --dport 443 \
    -m owner --uid-owner <uid> -j REDIRECT --to-port 4443

Setup via VPN app (no root)
────────────────────────────
  Use any SOCKS5-capable VPN interceptor (e.g. Proxifier on PC + USB
  tethering, or SocksDroid on Android) pointing to 127.0.0.1:4443.
  This proxy accepts plain TCP connections and forwards to TARGET_HOST.
"""

import socket
import struct
import threading
import zlib
import time
import sys
import os

TARGET_HOST = "ec2-13-126-233-176.ap-south-1.compute.amazonaws.com"
TARGET_PORT = 443
LISTEN_PORT = int(os.environ.get("SF3_PORT", 4443))
LISTEN_ADDR = "0.0.0.0"

PATCH_ENABLED = True

# ── WIN constants from verified real-win capture (3002601_battle_win) ────────
#
# These come from user_194.7.bin — the actual packet the game sent when it won.
# Items:  field[10] = two equipped items (IDs 1617 + 1618, both level 4)
# Stats:  field[13] = 71-byte fight stats blob
# Level:  field[14] = 28

_WIN_ITEMS = bytes.fromhex("0a0508d10c10040a0508d20c1004")
_WIN_STATS = bytes.fromhex(
    "081c104c1a03080c08220310120e2a03040704"
    "320c0000803fb2d77b3f0000803f"
    "3a0c000000000000000000000000"
    "420cd6a3603ed6a3603ed8a3603e"
    "4a03263c24"
    "5203000100"
)
_WIN_LEVEL = 28

# ── Protobuf helpers ──────────────────────────────────────────────────────────

def _enc_varint(v: int) -> bytes:
    out = b""
    while True:
        bits = v & 0x7F
        v >>= 7
        out += bytes([bits | (0x80 if v else 0)])
        if not v:
            break
    return out

def _enc_vf(fn: int, v: int) -> bytes:
    return _enc_varint((fn << 3) | 0) + _enc_varint(v)

def _enc_bf(fn: int, raw: bytes) -> bytes:
    return _enc_varint((fn << 3) | 2) + _enc_varint(len(raw)) + raw

def _read_varint(data: bytes, pos: int):
    val = shift = 0
    while pos < len(data):
        b = data[pos]; pos += 1
        val |= (b & 0x7F) << shift; shift += 7
        if not (b & 0x80):
            break
    return val, pos

def _decode_proto(data: bytes):
    fields = []; pos = 0
    while pos < len(data):
        try:
            tag, pos = _read_varint(data, pos)
            fn = tag >> 3; wire = tag & 7
            if fn == 0:
                break
            if wire == 0:
                val, pos = _read_varint(data, pos)
                fields.append((fn, 0, val))
            elif wire == 2:
                length, pos = _read_varint(data, pos)
                if pos + length > len(data):
                    break
                fields.append((fn, 2, data[pos:pos + length]))
                pos += length
            else:
                break
        except Exception:
            break
    return fields

# ── SF3 frame reader ──────────────────────────────────────────────────────────

def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("socket closed")
        buf += chunk
    return buf

def _read_frame(sock: socket.socket):
    """
    Read one complete SF3 frame from sock.
    Returns (raw_frame_bytes, payload_bytes).
    raw_frame_bytes can be forwarded verbatim.
    payload_bytes is the decompressed protobuf for 0x02 frames.
    """
    hdr = _recv_exact(sock, 1)
    t = hdr[0]

    if t == 0x01:
        sz_raw = _recv_exact(sock, 1)
        size = sz_raw[0]
        body = _recv_exact(sock, size)
        raw = hdr + sz_raw + body
        return raw, body

    elif t == 0x02:
        sz_raw = _recv_exact(sock, 4)
        size = struct.unpack_from("<I", sz_raw)[0]
        body = _recv_exact(sock, size)
        raw = hdr + sz_raw + body
        try:
            payload = zlib.decompress(body, -15)
        except zlib.error:
            payload = body
        return raw, payload

    else:
        raise ValueError(f"unknown frame type 0x{t:02x}")

# ── Command peek (fast path) ──────────────────────────────────────────────────

_TARGET_CMD = b"event_battle_finish_fight"

def _peek_cmd(payload: bytes) -> bytes | None:
    """Extract the command string from an outer proto payload. Returns bytes or None."""
    pos = 0
    while pos < len(payload):
        try:
            tag, pos = _read_varint(payload, pos)
            fn = tag >> 3; wire = tag & 7
            if wire == 0:
                _, pos = _read_varint(payload, pos)
            elif wire == 2:
                length, pos = _read_varint(payload, pos)
                if fn == 2:
                    return payload[pos:pos + length]
                pos += length
            else:
                break
        except Exception:
            break
    return None

# ── Patch logic ───────────────────────────────────────────────────────────────

def _patch_finish_fight(payload: bytes) -> bytes:
    """
    Given the decompressed outer-proto payload of an event_battle_finish_fight,
    returns a new re-encoded payload with result=WIN.

    Keeps: counter (field[1]), command (field[2]), battleID from inner (f[1]), seed (f[6])
    Fixes: inner field[4]=1 WIN, field[5]=rounds, field[7]=rounds
    Adds:  inner field[10]=items, field[13]=stats, field[14]=level
    """
    outer_fields = _decode_proto(payload)

    counter = None
    orig_inner = b""
    for fn, wire, val in outer_fields:
        if fn == 1 and wire == 0:
            counter = val
        elif fn == 3 and wire == 2:
            orig_inner = val

    inner_fields = _decode_proto(orig_inner)

    battle_id = None
    seed_bytes = None
    rounds = 3

    for fn, wire, val in inner_fields:
        if fn == 1 and wire == 0:
            battle_id = val
        elif fn == 6 and wire == 2:
            seed_bytes = val
        elif fn == 7 and wire == 0:
            rounds = max(val, 1)

    if battle_id is None:
        return payload

    if seed_bytes is None:
        seed_bytes = _enc_vf(1, int(time.time() * 1000))

    new_inner = (
        _enc_vf(1, battle_id)
        + _enc_vf(4, 1)
        + _enc_vf(5, rounds)
        + _enc_bf(6, seed_bytes)
        + _enc_vf(7, rounds)
        + _enc_bf(10, _WIN_ITEMS)
        + _enc_bf(13, _WIN_STATS)
        + _enc_vf(14, _WIN_LEVEL)
    )

    new_outer = (
        _enc_vf(1, counter)
        + _enc_bf(2, _TARGET_CMD)
        + _enc_bf(3, new_inner)
    )

    if len(new_outer) <= 255:
        return bytes([0x01, len(new_outer)]) + new_outer
    else:
        compressed = zlib.compress(new_outer, 1)[2:-4]
        return bytes([0x02]) + struct.pack("<I", len(compressed)) + compressed

# ── Per-connection proxy ──────────────────────────────────────────────────────

def _pipe_raw(src: socket.socket, dst: socket.socket, label: str):
    """Forward raw bytes src→dst with no processing (server→game direction)."""
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try: dst.shutdown(socket.SHUT_WR)
        except Exception: pass

def _pipe_game_to_server(game_sock: socket.socket, server_sock: socket.socket):
    """
    Forward game→server with SF3 framing awareness.
    Only event_battle_finish_fight gets patched. Everything else is forwarded
    as the original raw bytes — zero re-encode, zero latency hit.
    """
    patched_count = 0
    try:
        while True:
            raw_frame, payload = _read_frame(game_sock)

            if not PATCH_ENABLED:
                server_sock.sendall(raw_frame)
                continue

            cmd = _peek_cmd(payload)

            if cmd == _TARGET_CMD:
                new_frame = _patch_finish_fight(payload)
                server_sock.sendall(new_frame)
                patched_count += 1
                _log(f"[PATCH #{patched_count}] finish_fight → WIN  "
                     f"orig={len(raw_frame)}B  patched={len(new_frame)}B")
            else:
                server_sock.sendall(raw_frame)

    except (ConnectionError, OSError):
        pass
    except Exception as e:
        _log(f"[!] pipe_game_to_server error: {e}")
    finally:
        try: server_sock.shutdown(socket.SHUT_WR)
        except Exception: pass

def _handle_connection(game_sock: socket.socket, addr):
    _log(f"[+] new connection from {addr[0]}:{addr[1]}")
    server_sock = None
    try:
        server_sock = socket.create_connection((TARGET_HOST, TARGET_PORT), timeout=10)
        server_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        game_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        t_srv2game = threading.Thread(
            target=_pipe_raw,
            args=(server_sock, game_sock, "srv→game"),
            daemon=True,
        )
        t_srv2game.start()

        _pipe_game_to_server(game_sock, server_sock)
        t_srv2game.join(timeout=5)

    except Exception as e:
        _log(f"[!] connection {addr}: {e}")
    finally:
        for s in (game_sock, server_sock):
            if s:
                try: s.close()
                except Exception: pass
        _log(f"[-] closed {addr[0]}:{addr[1]}")

# ── Logging ───────────────────────────────────────────────────────────────────

def _log(msg: str):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)

# ── Entry point ───────────────────────────────────────────────────────────────

def main():
    _log(f"SF3 surgical interceptor starting")
    _log(f"  Listen : {LISTEN_ADDR}:{LISTEN_PORT}")
    _log(f"  Target : {TARGET_HOST}:{TARGET_PORT}")
    _log(f"  Patch  : {'ENABLED' if PATCH_ENABLED else 'DISABLED (monitor mode)'}")
    _log(f"")
    _log(f"  Patching: event_battle_finish_fight  field[4]→WIN(1)")
    _log(f"  All other packets forwarded as raw bytes")
    _log(f"")

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_ADDR, LISTEN_PORT))
    server.listen(8)
    _log(f"Listening on port {LISTEN_PORT} ...")

    try:
        while True:
            client_sock, addr = server.accept()
            t = threading.Thread(
                target=_handle_connection,
                args=(client_sock, addr),
                daemon=True,
            )
            t.start()
    except KeyboardInterrupt:
        _log("Shutting down.")
    finally:
        server.close()

if __name__ == "__main__":
    main()
