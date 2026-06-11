"""
sf3_battlewin.py
================
Shadow Fight 3 — automated event battle win tool.

Flow:
  1. Download + decrypt latest config zip from nekki CDN
  2. Parse all event battles (ASCENSION / SURVIVAL) with their IDs and round counts
  3. Show a list — user picks a battle ID
  4. Connect to server, log in, and submit a Win for that battle

Protocol (plain TCP port 443, no TLS):
  Small packet:  0x01 + 1B length + raw protobuf
  Large packet:  0x02 + 4B LE length + raw-deflate-compressed protobuf
  Outer proto:   field[1]=counter  field[2]=command  field[3]=params_bytes

Verified WIN finish_fight params (from 3002601_battle_win capture, user_194.7.bin):
  field[1]  varint = battleId            WIN value: 3001601
  field[4]  varint = 1                   1 = WIN  (game sends 3=LOSS on defeat — DO NOT copy that)
  field[5]  varint = rounds              wonRounds (absent in loss packet — must be added)
  field[6]  bytes  = {field[1]=ts_ms}    live timestamp nested proto
  field[7]  varint = rounds              totalRounds (game sends 1 on loss)
  field[10] bytes  = items               equipped items (empty in loss — must be added)
  field[13] bytes  = stats               71-byte fight stats (2-byte junk in loss — must be real)
  field[14] varint = 28                  player level (absent in loss — must be added)

NOTE: battlem/user_3 shows the game's NATURAL LOSS packet — those values (field[4]=3,
field[7]=1, empty items/stats) are what a LOSS looks like. Do not use them for a win.

Usage:
  python3 sf3_battlewin.py               # interactive (pick battle)
  python3 sf3_battlewin.py --id 3000602  # direct: win specific battle
"""

import socket, struct, zlib, hashlib, json, time, re, sys
import urllib.request

HOSTS = [
    "ec2-13-126-233-176.ap-south-1.compute.amazonaws.com",
    "ec2-52-66-28-201.ap-south-1.compute.amazonaws.com",
]
PORT = 443

GUID          = "289a6ff9-15d5-48aa-95f2-dfcfebedfab8"
GAME_VERSION  = "1.45.0.3.16663-prod"
SHORT_VERSION = "1.45.0"
DEVICE_MODEL  = "samsung SM-X115"
ANDROID_ID    = "719e3418841cabcd"

AES_KEY    = "08050674cc9ab867197f0cad55a770ca"
AES_IV     = "653e0715236e0f734f1ebf64228b322d"
CONFIG_URL = "https://sf3assets.nekki.com/prod/configs/1.45.0.3.16663-prod_all.zip"

HANDSHAKE_TX = bytes.fromhex(
    "011b0801120948414e445348414b451a0c0a0a5346412d4e4542552d31"
)

ORIGINAL_INNER = bytes.fromhex(
    "0806"
    "12620801125e"
    "7b226c6f67696e223a2232383961366666392d313564352d343861612d393566322d"
    "646663666562656466616238222c2270617373776f7264223a22"
    "6536633666656561653932363663306335343439353632396663636236623433"
    "22"
    "7d"
    "1a7b" "080d" "1277"
    "7b2274223a22342f3041646b564c50776557587974552d6f2d6b517a386b5a31"
    "6871615050657859754b5258707149533979454a6e7651775058443245475a64"
    "71434d5357755f353437714f555751222c226964223a22673136363133383934"
    "363134373335323534393337222c2265223a747275657d"
    "1a14" "0806" "1210" "3731396533343138383431636162636422"
    "dc01"
    "7b22706c6174666f726d223a22416e64726f6964222c2276223a223139323032"
    "222c226170705f6964223a22636f6d2e6e656b6b692e736861646f7766696768"
    "7433222c2266223a224630303046414236353444434336463830413541413136"
    "41373334434345323444423244323338" "43222c22626e756d626572223a223139"
    "323032222c22626e616d65223a22556e697479436c69656e745f536861646f77"
    "4669676874335f556e697479436c69656e74536861646f7746696768743352656c"
    "656173655f436f6e66696775726174696f6e416e64726f6964227d"
)
ORIGINAL_PASSWORD = b"e6c6feeae9266c0c54495629fccb6b43"

LOG_JSON_TEMPLATE = (
    '{"Name":"Battle_Log","Log":null,'
    '"Params":{"Blocks_R1":5,"Hits_R1":22,"ShadowForm_Toggle_R1":4,'
    '"Blocks_R2":1,"Hits_R2":5,"ShadowForm_Toggle_R2":2,'
    '"Hits_R3":5,"ShadowForm_Toggle_R3":1},'
    '"creg":1612247644,"battleID":__BATTLE_ID__,"fightID":0,"variant":null,'
    '"rounds":0,"result":"Win","vfd":1,'
    '"fpsAverageValue":59.2956657,"fpsStandardDeviation":2.51426435,'
    '"fpsPhysicsAverageValue":60.54593,"fpsPhysicsStandardDeviation":0.004833738,'
    '"subtype":"fps_counter",'
    '"sim":{"simAverageValue":2.2175907668231605,'
    '"simStandardDeviation":4.1669605232503182,"median":1.448},'
    '"device_info":{"device":"samsung SM-X115",'
    '"OS":"Android OS 16 / API-36 (BP2A.250605.031.A3/X115XXS8DZA6)",'
    '"RAM":3642,"CPU":8,"screenWidth":1340,"screenHeight":800,'
    '"processorFrequencyMHz":2200,"graphicsDeviceName":"Mali-G57 MC2",'
    '"graphicsDeviceVersion":"OpenGL ES 3.2 v1.r54p1-09bet0.ffb9e377f5d7c95fe2471f3c463d2515",'
    '"graphicsMemorySize":1024,"graphicsShaderLevel":50,'
    '"apiLevel":"36","OSVersion":"36.0","gamepad":false,'
    '"id_FULL":"719e3418841cabcd","qualityPreset":"high",'
    '"systemLanguage":"English","network":"wifi",'
    '"build_version":19202,"localization":"English"},'
    '"etype":"CLIENT_FIGHT_END","cid":124,"pl":55,'
    '"cts":__CTS__,'
    '"plf":"Android","v":"1.45.0.3","fv":"1.45.0.3.16663-prod",'
    '"sid":__SID__,"onl":true,"md":"Fight"}'
)

# ── Protobuf helpers ─────────────────────────────────────────────────────────

def enc_varint(v: int) -> bytes:
    out = b""
    while True:
        bits = v & 0x7F; v >>= 7
        out += bytes([bits | (0x80 if v else 0)])
        if not v: break
    return out

def enc_str(fn: int, s: str) -> bytes:
    e = s.encode()
    return enc_varint((fn << 3) | 2) + enc_varint(len(e)) + e

def enc_bytes(fn: int, raw: bytes) -> bytes:
    return enc_varint((fn << 3) | 2) + enc_varint(len(raw)) + raw

def enc_varint_field(fn: int, v: int) -> bytes:
    return enc_varint((fn << 3) | 0) + enc_varint(v)

def read_varint(data: bytes, pos: int):
    val, shift = 0, 0
    while pos < len(data):
        b = data[pos]; pos += 1
        val |= (b & 0x7F) << shift; shift += 7
        if not (b & 0x80): break
    return val, pos

def decode_proto(data: bytes):
    fields, pos = [], 0
    while pos < len(data):
        try:
            tag, pos = read_varint(data, pos); fn = tag >> 3; wire = tag & 7
            if fn == 0: break
            if wire == 0:
                val, pos = read_varint(data, pos); fields.append((fn, 0, val))
            elif wire == 2:
                length, pos = read_varint(data, pos)
                if pos + length > len(data): break
                fields.append((fn, 2, data[pos:pos+length])); pos += length
            else: break
        except Exception: break
    return fields

def try_utf8(raw: bytes):
    try:
        s = raw.decode("utf-8")
        if all(32 <= ord(c) < 127 or c in "\t\n\r" for c in s): return s
    except Exception: pass
    return None

# ── Packet framing ───────────────────────────────────────────────────────────

def small_pkt(payload: bytes) -> bytes:
    assert len(payload) <= 255
    return bytes([0x01, len(payload)]) + payload

def large_pkt(payload: bytes) -> bytes:
    compressed = zlib.compress(payload, 6)[2:-4]
    return bytes([0x02]) + struct.pack("<I", len(compressed)) + compressed

def build_outer(counter: int, cmd: str, inner: bytes = b"") -> bytes:
    p  = enc_varint_field(1, counter)
    p += enc_str(2, cmd)
    if inner: p += enc_bytes(3, inner)
    return p

def recv_exact(sock, n: int):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk: return None
        buf += chunk
    return buf

def recv_packet(sock):
    hdr = recv_exact(sock, 1)
    if not hdr: return None, None
    t = hdr[0]
    if t == 0x01:
        sz = recv_exact(sock, 1)
        if not sz: return None, None
        size = sz[0]
    elif t == 0x02:
        sz = recv_exact(sock, 4)
        if not sz: return None, None
        size = struct.unpack("<I", sz)[0]
    else:
        print(f"[!] Unknown frame 0x{t:02x}"); return None, None
    data = recv_exact(sock, size)
    if data is None: return None, None
    if t == 0x02:
        try: data = zlib.decompress(data, -15)
        except zlib.error as e: print(f"[!] Decompress: {e}"); return None, None
    return t, data

def parse_outer(data: bytes):
    counter = cmd = None; inner = b""; extra = {}
    for fn, wire, val in decode_proto(data):
        if fn == 1 and wire == 0: counter = val
        elif fn == 2 and wire == 2:
            s = try_utf8(val)
            if s: cmd = s
        elif fn == 3 and wire == 2: inner = val
        elif fn == 4 and wire == 0: extra["code"] = val
        elif fn == 5 and wire == 2: extra["msg"] = val.decode(errors="replace")
    return counter, cmd, inner, extra

# ── Session helpers ──────────────────────────────────────────────────────────

def extract_session_token(data: bytes):
    text = "".join(chr(b) if 32 <= b < 127 else " " for b in data)
    m = re.findall(r"[0-9a-f]{7,}-[0-9a-f]{6,}-[0-9a-f]{6,}", text)
    return m[0] if m else None

def drain_until(sock, target_cmd: str, timeout: float = 10.0):
    sock.settimeout(timeout)
    while True:
        try:
            _, data = recv_packet(sock)
            if data is None: print("[!] Connection closed"); return None
            ctr, cmd, inner, extra = parse_outer(data)
            if extra.get("code"):
                print(f"  [<<] {cmd!r}  code={extra['code']}  {extra.get('msg','')[:60]}")
            else:
                print(f"  [<<] ctr={ctr}  cmd={cmd!r}  {len(inner)}B")
            if cmd == target_cmd: return inner
            if cmd == "ping":
                sock.sendall(make_ping_ack(ctr, inner))
                print(f"  [>>] ping ack ctr={ctr}")
        except socket.timeout:
            print(f"  [!] Timeout waiting for {target_cmd!r}"); return None

def drain_all(sock, timeout: float = 3.0, max_packets: int = 12):
    sock.settimeout(timeout); packets = []; ping_acked = set()
    while len(packets) < max_packets:
        try:
            _, data = recv_packet(sock)
            if data is None: break
            ctr, cmd, inner, extra = parse_outer(data)
            packets.append((ctr, cmd, inner, extra))
            if extra.get("code"):
                print(f"  [<<] {cmd!r}  code={extra['code']}  {extra.get('msg','')[:60]}")
            else:
                print(f"  [<<] ctr={ctr}  cmd={cmd!r}  {len(inner)}B")
            if cmd == "ping" and ctr not in ping_acked:
                sock.sendall(make_ping_ack(ctr, inner))
                print(f"  [>>] ping ack ctr={ctr}")
                ping_acked.add(ctr)
        except socket.timeout: break
    return packets

# ── Packet builders ──────────────────────────────────────────────────────────

def make_login_packet(session_token: str) -> bytes:
    pw = hashlib.md5((session_token + GUID).encode()).hexdigest().encode()
    inner = ORIGINAL_INNER.replace(ORIGINAL_PASSWORD, pw)
    if inner == ORIGINAL_INNER:
        raise ValueError("Password substitution failed")
    return large_pkt(build_outer(2, "LOGIN", inner))

def make_ping(counter: int) -> bytes:
    ts_submsg = b"\x08" + enc_varint(int(time.time() * 1000))
    fingerprint = "36455C3E36075A6545181B460AF344FF1DCD053F"
    net_inner = enc_str(1, "net_data") + enc_str(2, fingerprint)
    inner = enc_bytes(1, ts_submsg) + enc_bytes(2, net_inner)
    return small_pkt(build_outer(counter, "ping", inner))

def make_ping_ack(server_counter: int, server_inner: bytes) -> bytes:
    server_ts = b""
    for fn, wire, val in decode_proto(server_inner):
        if fn == 1 and wire == 2: server_ts = val; break
    our_ts = b"\x08" + enc_varint(int(time.time() * 1000))
    ack_inner = enc_bytes(1, server_ts) + enc_bytes(2, our_ts)
    return small_pkt(build_outer(server_counter, "ping", ack_inner))

def make_battle_id_inner(battle_id: int) -> bytes:
    return enc_varint_field(1, battle_id)

# WIN constants captured from 3002601_battle_win / user_194.7.bin
# These are the values the game sends on a REAL WIN — verified field by field.
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


def _make_finish_fight_inner(battle_id: int, rounds: int = 3) -> bytes:
    """
    Builds the params inner for event_battle_finish_fight — WIN variant.

    Values verified against 3002601_battle_win / user_194.7.bin (real game win):

      field[1]  varint = battle_id
      field[4]  varint = 1          WIN  (3 = LOSS — do NOT use the loss packet values)
      field[5]  varint = rounds      wonRounds
      field[6]  bytes  = ts_proto    live timestamp {field[1]=unix_ms}
      field[7]  varint = rounds      totalRounds
      field[10] bytes  = _WIN_ITEMS  equipped items from real win
      field[13] bytes  = _WIN_STATS  fight stats from real win (71 bytes)
      field[14] varint = _WIN_LEVEL  player level from real win
    """
    ts_proto = enc_varint_field(1, int(time.time() * 1000))

    inner  = enc_varint_field(1, battle_id)
    inner += enc_varint_field(4, 1)
    inner += enc_varint_field(5, rounds)
    inner += enc_bytes(6, ts_proto)
    inner += enc_varint_field(7, rounds)
    inner += enc_bytes(10, _WIN_ITEMS)
    inner += enc_bytes(13, _WIN_STATS)
    inner += enc_varint_field(14, _WIN_LEVEL)
    return inner

def make_finish_fight_win(battle_id: int, rounds: int = 3) -> bytes:
    """Standalone packet builder. counter=0 placeholder — caller wraps with build_outer if needed."""
    inner = _make_finish_fight_inner(battle_id, rounds)
    outer = build_outer(0, "event_battle_finish_fight", inner)
    return large_pkt(outer) if len(outer) > 255 else small_pkt(outer)

def make_log_packet(counter: int, battle_id: int, sid: int) -> bytes:
    log_str = (LOG_JSON_TEMPLATE
               .replace("__BATTLE_ID__", str(battle_id))
               .replace("__CTS__", str(int(time.time() * 1000)))
               .replace("__SID__", str(sid)))
    inner = enc_bytes(1, log_str.encode())
    return large_pkt(build_outer(counter, "log", inner))

# ── Config download + decryption ─────────────────────────────────────────────

def _zip_entries(buf: bytes):
    eocd = -1
    for i in range(len(buf) - 22, -1, -1):
        if buf[i:i+4] == b'PK\x05\x06': eocd = i; break
    if eocd < 0: raise ValueError("No EOCD record")
    cd_off = struct.unpack_from('<I', buf, eocd+16)[0]
    num    = struct.unpack_from('<H', buf, eocd+10)[0]
    entries, pos = [], cd_off
    for _ in range(num):
        if buf[pos:pos+4] != b'PK\x01\x02': break
        comp   = struct.unpack_from('<H', buf, pos+10)[0]
        csz    = struct.unpack_from('<I', buf, pos+20)[0]
        usz    = struct.unpack_from('<I', buf, pos+24)[0]
        fn_len = struct.unpack_from('<H', buf, pos+28)[0]
        ex_len = struct.unpack_from('<H', buf, pos+30)[0]
        cm_len = struct.unpack_from('<H', buf, pos+32)[0]
        lh_off = struct.unpack_from('<I', buf, pos+42)[0]
        name   = buf[pos+46:pos+46+fn_len].decode()
        entries.append((name, comp, csz, usz, lh_off))
        pos += 46 + fn_len + ex_len + cm_len
    return entries

def _extract_entry(buf: bytes, entry) -> bytes:
    name, comp, csz, usz, lh_off = entry
    fn_len = struct.unpack_from('<H', buf, lh_off+26)[0]
    ex_len = struct.unpack_from('<H', buf, lh_off+28)[0]
    data_off = lh_off + 30 + fn_len + ex_len
    raw = buf[data_off:data_off+csz]
    if comp == 0: return raw
    return zlib.decompress(raw, -15)

def _build_aes_tables():
    Sfixed = [
        0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
        0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
        0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
        0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
        0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
        0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
        0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
        0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
        0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
        0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
        0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
        0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
        0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
        0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
        0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
        0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16,
    ]
    Si2 = [0]*256
    for i, v in enumerate(Sfixed): Si2[v] = i

    def gmul(a, b):
        p = 0
        for _ in range(8):
            if b & 1: p ^= a
            hi = a & 0x80; a = (a << 1) & 0xff
            if hi: a ^= 0x1b
            b >>= 1
        return p

    m9  = [gmul(i, 9)  for i in range(256)]
    m11 = [gmul(i, 11) for i in range(256)]
    m13 = [gmul(i, 13) for i in range(256)]
    m14 = [gmul(i, 14) for i in range(256)]
    rcon = [0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36]
    return Sfixed, Si2, m9, m11, m13, m14, rcon

_S, _SI, _M9, _M11, _M13, _M14, _RCON = _build_aes_tables()

def _sub_word(w):
    return (_S[w>>24]<<24)|(_S[(w>>16)&0xff]<<16)|(_S[(w>>8)&0xff]<<8)|_S[w&0xff]

def _rot_word(w):
    return ((w<<8)|(w>>24))&0xffffffff

def _key_expand(key: bytes):
    w = [int.from_bytes(key[i:i+4],'big') for i in range(0,16,4)]
    for i in range(4, 44):
        t = w[i-1]
        if i % 4 == 0: t = _sub_word(_rot_word(t)) ^ (_RCON[i//4-1] << 24)
        w.append(w[i-4] ^ t)
    return [[w[r*4], w[r*4+1], w[r*4+2], w[r*4+3]] for r in range(11)]

def _aes128_decrypt_block(blk: bytes, rk) -> bytes:
    s = [[blk[r+4*c] for c in range(4)] for r in range(4)]
    for c in range(4):
        for r in range(4): s[r][c] ^= (rk[10][c] >> (24-r*8)) & 0xff
    for rnd in range(9, 0, -1):
        s[1][0],s[1][1],s[1][2],s[1][3] = s[1][3],s[1][0],s[1][1],s[1][2]
        s[2][0],s[2][1],s[2][2],s[2][3] = s[2][2],s[2][3],s[2][0],s[2][1]
        s[3][0],s[3][1],s[3][2],s[3][3] = s[3][1],s[3][2],s[3][3],s[3][0]
        for r in range(4):
            for c in range(4): s[r][c] = _SI[s[r][c]]
        for c in range(4):
            for r in range(4): s[r][c] ^= (rk[rnd][c] >> (24-r*8)) & 0xff
        for c in range(4):
            a,b,d,e = s[0][c],s[1][c],s[2][c],s[3][c]
            s[0][c]=_M14[a]^_M11[b]^_M13[d]^_M9[e]
            s[1][c]=_M9[a]^_M14[b]^_M11[d]^_M13[e]
            s[2][c]=_M13[a]^_M9[b]^_M14[d]^_M11[e]
            s[3][c]=_M11[a]^_M13[b]^_M9[d]^_M14[e]
    s[1][0],s[1][1],s[1][2],s[1][3] = s[1][3],s[1][0],s[1][1],s[1][2]
    s[2][0],s[2][1],s[2][2],s[2][3] = s[2][2],s[2][3],s[2][0],s[2][1]
    s[3][0],s[3][1],s[3][2],s[3][3] = s[3][1],s[3][2],s[3][3],s[3][0]
    for r in range(4):
        for c in range(4): s[r][c] = _SI[s[r][c]]
    for c in range(4):
        for r in range(4): s[r][c] ^= (rk[0][c] >> (24-r*8)) & 0xff
    return bytes(s[r][c] for c in range(4) for r in range(4))

def aes128_cbc_decrypt(data: bytes, key: bytes, iv: bytes) -> bytes:
    rk = _key_expand(key)
    out = bytearray(); prev = iv
    for i in range(0, len(data), 16):
        blk = data[i:i+16]
        dec = _aes128_decrypt_block(blk, rk)
        out += bytes(a^b for a,b in zip(dec, prev))
        prev = blk
    pad = out[-1]
    return bytes(out[:-pad]) if 1 <= pad <= 16 else bytes(out)

def download_and_decrypt_config(url: str = CONFIG_URL) -> bytes:
    print(f"[*] Downloading config from {url}")
    with urllib.request.urlopen(url, timeout=30) as r:
        outer_data = r.read()
    print(f"    Got {len(outer_data):,} bytes")
    entries = _zip_entries(outer_data)
    encs = [(e, len(e[0].split('/')[-1])) for e in entries if e[0].endswith('.enc')]
    if not encs: raise RuntimeError("No .enc file found in outer zip")
    enc_entry = min(encs, key=lambda x: x[1])[0]
    print(f"    Decrypting: {enc_entry[0]}")
    enc_data = _extract_entry(outer_data, enc_entry)
    return aes128_cbc_decrypt(enc_data, bytes.fromhex(AES_KEY), bytes.fromhex(AES_IV))

# ── Battle parser ─────────────────────────────────────────────────────────────

KNOWN_TEMPLATES = {
    "BattleEventDefault.ASCENSION": "ASCENSION",
    "BattleEventDefault.SURVIVAL":  "SURVIVAL",
    "BattleEventDefault.HUB":       "HUB",
}

VISIBLE_SHORT = {
    "VisibleBattleType.NORMAL":     "NORMAL",
    "VisibleBattleType.HARD":       "HARD",
    "VisibleBattleType.EVENT":      "EVENT",
    "VisibleBattleType.STANDART":   "STANDARD",
    "VisibleBattleType.GRAND":      "GRAND",
    "VisibleBattleType.TOURNAMENT": "TOURNAMENT",
    "VisibleBattleType.SINGLE":     "SINGLE",
    "VisibleBattleType.DUEL":       "DUEL",
    "VisibleBattleType.BOSS":       "BOSS",
    "VisibleBattleType.GIG":        "GIG",
    "VisibleBattleType.CHOICE":     "CHOICE",
    "VisibleBattleType.BIRTHDAY":   "BIRTHDAY",
    "VisibleBattleType.MEMORY":     "MEMORY",
}

def _parse_battles_from_js(text: str, source_file: str) -> list:
    results = []
    id_re = re.compile(r'\bID\s*:\s*(\d{5,})')
    for m in id_re.finditer(text):
        battle_id = int(m.group(1))
        prefix = text[max(0, m.start()-300):m.start()]
        name_m = re.search(r'([A-Z][A-Z0-9_]{3,})\s*:\s*\{[^{]*$', prefix)
        name = name_m.group(1) if name_m else "?"
        ctx = text[m.start():min(len(text), m.start()+1500)]
        template_m = re.search(r'DefaultTemplate\s*:\s*(BattleEventDefault\.\w+)', ctx)
        rounds_m   = re.search(r'RoundsToWin\s*:\s*(\d+)', ctx)
        visible_m  = re.search(r'VisibleType\s*:\s*(VisibleBattleType\.\w+)', ctx)
        template = template_m.group(1) if template_m else None
        if template is None: continue
        rounds  = int(rounds_m.group(1)) if rounds_m else 3
        visible = VISIBLE_SHORT.get(visible_m.group(1) if visible_m else "", "?")
        tshort  = KNOWN_TEMPLATES.get(template, template.replace("BattleEventDefault.", ""))
        results.append({"id": battle_id, "name": name, "template": tshort,
                        "rounds": rounds, "visible": visible, "source": source_file})
    return results

def parse_all_battles(config_zip_bytes: bytes) -> list:
    entries = _zip_entries(config_zip_bytes)
    js_files = [e for e in entries
                if e[0].startswith('scripts/features/events') and e[0].endswith('.js')]
    all_battles = []
    for ef in js_files:
        try:
            text = _extract_entry(config_zip_bytes, ef).decode('utf-8', errors='replace')
            all_battles.extend(_parse_battles_from_js(text, ef[0].split('/')[-1]))
        except Exception: pass
    seen = {}
    for b in all_battles:
        bid = b['id']
        if bid not in seen or (b['name'] != '?' and seen[bid]['name'] == '?'):
            seen[bid] = b
    return sorted(seen.values(), key=lambda b: b['id'])

def print_battle_table(battles: list) -> None:
    by_source = {}
    for b in battles:
        by_source.setdefault(b['source'], []).append(b)
    print(f"\n{'─'*80}")
    print(f"  {'ID':>8}  {'TYPE':10}  {'VISIBLE':10}  {'ROUNDS':>6}  NAME")
    print(f"{'─'*80}")
    for src, blist in sorted(by_source.items()):
        print(f"\n  [{src}]")
        for b in blist:
            print(f"  {b['id']:>8}  {b['template']:10}  {b['visible']:10}  "
                  f"{b['rounds']:>6}  {b['name']}")
    print(f"{'─'*80}\n")

# ── Server response printer ───────────────────────────────────────────────────

def print_finish_response(inner: bytes) -> None:
    print(f"\n{'─'*60}")
    print(f"  event_battle_finish_fight  server response  ({len(inner):,}B)")
    print(f"{'─'*60}")
    for fn, wire, val in decode_proto(inner):
        if wire == 0:
            print(f"  field[{fn}] = {val}")
        elif wire == 2:
            txt = try_utf8(val)
            if txt:
                print(f"  field[{fn}] str = {txt[:200]}")
            else:
                print(f"  field[{fn}] bytes({len(val)}) = {val.hex()[:60]}")
                for fn2, wire2, val2 in decode_proto(val):
                    if wire2 == 0: print(f"    field[{fn2}] = {val2}")
                    elif wire2 == 2:
                        txt2 = try_utf8(val2)
                        if txt2: print(f"    field[{fn2}] str = {txt2[:120]}")
                        else: print(f"    field[{fn2}] bytes({len(val2)}) = {val2.hex()[:60]}")
    print(f"{'─'*60}\n")

# ── Main fight loop ───────────────────────────────────────────────────────────

def run_fight(battle_id: int, rounds_to_win: int, battle_name: str,
              battle_template: str, host: str = None):
    if host is None:
        host = HOSTS[0]

    is_ascension = (battle_template == "ASCENSION")

    print(f"\n{'═'*60}")
    print(f"  SF3 Battle Win")
    print(f"  Battle : {battle_id}  ({battle_name})")
    print(f"  Type   : {battle_template}")
    print(f"  Host   : {host}:{PORT}")
    print(f"{'═'*60}\n")

    session_id = 60706

    with socket.create_connection((host, PORT), timeout=12) as sock:
        sock.settimeout(10)

        # ── Phase 1: Handshake ─────────────────────────────────────────
        print("[*] Phase 1: Handshake")
        sock.sendall(HANDSHAKE_TX)
        _, raw_hs = recv_packet(sock)
        if raw_hs is None:
            print("[!] No handshake response"); return False
        session_token = extract_session_token(raw_hs)
        if not session_token:
            print(f"[!] No session token.  Raw: {raw_hs.hex()[:60]}"); return False
        print(f"  [+] Session token: {session_token}")

        # ── Phase 2: Login ─────────────────────────────────────────────
        print("\n[*] Phase 2: Login")
        pw = hashlib.md5((session_token + GUID).encode()).hexdigest()
        print(f"  [+] MD5 password: {pw}")
        sock.sendall(make_login_packet(session_token))
        print(f"  [>>] LOGIN")

        join_inner = drain_until(sock, "join_zone", timeout=12)
        if join_inner is None:
            print("  [!] join_zone not received — continuing anyway")
        else:
            print(f"  [+] join_zone OK  ({len(join_inner)}B)")
            for fn, wire, val in decode_proto(join_inner):
                if fn == 1 and wire == 0:
                    session_id = val
                    print(f"  [+] Session ID: {session_id}")
                    break

        # ── Phase 3: Ping ──────────────────────────────────────────────
        print("\n[*] Phase 3: Ping")
        counter = 3
        sock.sendall(make_ping(counter)); print(f"  [>>] ping ctr={counter}")
        counter += 1
        drain_all(sock, timeout=4)

        # ── Phase 4: Fight sequence ────────────────────────────────────
        print(f"\n[*] Phase 4: Fight  (battle_id={battle_id})")

        battle_inner = make_battle_id_inner(battle_id)

        if is_ascension:
            outer = build_outer(counter, "activate_ascension", battle_inner)
            pkt = small_pkt(outer) if len(outer) <= 255 else large_pkt(outer)
            sock.sendall(pkt)
            print(f"  [>>] activate_ascension  ctr={counter}")
            counter += 1
            drain_all(sock, timeout=4)

        outer = build_outer(counter, "event_battle_start_fight", battle_inner)
        pkt = small_pkt(outer) if len(outer) <= 255 else large_pkt(outer)
        sock.sendall(pkt)
        print(f"  [>>] event_battle_start_fight  ctr={counter}")
        counter += 1
        drain_all(sock, timeout=4)

        log_pkt = make_log_packet(counter, battle_id, session_id)
        sock.sendall(log_pkt)
        print(f"  [>>] log  ctr={counter}")
        counter += 1

        # Build finish_fight with corrected params (verified against capture)
        finish_inner = _make_finish_fight_inner(battle_id)
        outer = build_outer(counter, "event_battle_finish_fight", finish_inner)
        finish_pkt = small_pkt(outer) if len(outer) <= 255 else large_pkt(outer)
        sock.sendall(finish_pkt)
        print(f"  [>>] event_battle_finish_fight  ctr={counter}  (WIN)")
        counter += 1

        # ── Read finish response ───────────────────────────────────────
        finish_response = drain_until(sock, "event_battle_finish_fight", timeout=12)
        if finish_response is not None:
            print_finish_response(finish_response)
            return True
        else:
            print("[!] No finish_fight response — draining remaining packets:")
            drain_all(sock, timeout=5)
            return False

# ── Entry point ───────────────────────────────────────────────────────────────

def main():
    import argparse
    parser = argparse.ArgumentParser(description="Shadow Fight 3 — event battle win")
    parser.add_argument("--id", type=int, help="Battle ID to win directly")
    args = parser.parse_args()

    try:
        config_bytes = download_and_decrypt_config()
    except Exception as e:
        print(f"[!] Config download failed: {e}")
        config_bytes = None

    battles = []
    battle_map = {}
    if config_bytes:
        print("[*] Parsing battles from config...")
        battles = parse_all_battles(config_bytes)
        battle_map = {b['id']: b for b in battles}
        print(f"    Found {len(battles)} event battles")

    if battles:
        print_battle_table(battles)

    if args.id:
        direct_id = args.id
    else:
        try:
            raw = input("Enter battle ID to win: ").strip()
            direct_id = int(raw)
        except (ValueError, EOFError):
            print("[!] Invalid input"); sys.exit(1)

    if direct_id in battle_map:
        b = battle_map[direct_id]
        rounds = b['rounds']; name = b['name']; tmpl = b['template']
    else:
        print(f"[!] Battle {direct_id} not found in config — using defaults")
        rounds = 3; name = f"battle_{direct_id}"; tmpl = "ASCENSION"

    for host in HOSTS:
        print(f"\n[*] Trying host: {host}")
        try:
            ok = run_fight(direct_id, rounds, name, tmpl, host)
            if ok:
                print("\n[+] Done — Win submitted successfully.")
                return
        except Exception as e:
            print(f"[!] Error with {host}: {e}")
    print("\n[!] All hosts failed.")


if __name__ == "__main__":
    main()
