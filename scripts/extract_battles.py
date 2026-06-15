"""
extract_battles.py
==================
Fetches the latest SF3 config archive, decrypts it, parses all event battle
definitions, and writes data/battles.json.

Run by the GitHub Actions workflow. Also runnable locally:
    python3 scripts/extract_battles.py

    # Skip download/decrypt and use a local decrypted inner-config ZIP:
    python3 scripts/extract_battles.py --local-zip /path/to/inner_config.zip

Output format (data/battles.json):
{
  "generated": "2026-06-11T06:00:00Z",
  "version":   "1.45.0.10.16666-prod",
  "battles": {
    "3002601": 3,
    "3000602": 3,
    "3101111": [2, 2, 3],
    ...
  }
}
Keys are battle IDs (strings).
Values are either:
  - int   → single-fight battle, RoundsToWin applies to the whole fight
  - list  → multi-fight (skeleton) battle; each element is RoundsToWin for
             that sub-battle index (0-indexed, matching server field[3]).
"""

import argparse
import json
import os
import random
import re
import struct
import sys
import urllib.request
import zlib
from datetime import datetime, timezone

# ── Balance endpoint ──────────────────────────────────────────────────────────
BALANCE_URL = (
    "https://sfxlbalt.nekki.com:9043/balance"
    "?w=IN&fv=1.485.0.10.6-prod&rand={rand}&p=Android"
)

# ── AES-128-CBC key + IV (from verified SF3 config decryption) ────────────────
AES_KEY = bytes.fromhex("08050674cc9ab867197f0cad55a770ca")
AES_IV  = bytes.fromhex("653e0715236e0f734f1ebf64228b322d")

# ── Output path ───────────────────────────────────────────────────────────────
OUT_FILE = os.path.join(os.path.dirname(__file__), "..", "data", "battles.json")


# ── Pure-Python AES-128-CBC decrypt ──────────────────────────────────────────

def _build_aes_tables():
    S = [
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
    SI = [0] * 256
    for i, v in enumerate(S):
        SI[v] = i

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
    return S, SI, m9, m11, m13, m14, rcon

_S, _SI, _M9, _M11, _M13, _M14, _RCON = _build_aes_tables()


def _sub_word(w):
    return (_S[w>>24]<<24)|(_S[(w>>16)&0xff]<<16)|(_S[(w>>8)&0xff]<<8)|_S[w&0xff]

def _rot_word(w):
    return ((w<<8)|(w>>24))&0xffffffff

def _key_expand(key: bytes):
    w = [int.from_bytes(key[i:i+4], 'big') for i in range(0, 16, 4)]
    for i in range(4, 44):
        t = w[i-1]
        if i % 4 == 0:
            t = _sub_word(_rot_word(t)) ^ (_RCON[i//4-1] << 24)
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
            s[0][c] = _M14[a]^_M11[b]^_M13[d]^_M9[e]
            s[1][c] = _M9[a] ^_M14[b]^_M11[d]^_M13[e]
            s[2][c] = _M13[a]^_M9[b] ^_M14[d]^_M11[e]
            s[3][c] = _M11[a]^_M13[b]^_M9[d] ^_M14[e]
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
    out = bytearray()
    prev = iv
    for i in range(0, len(data), 16):
        blk = data[i:i+16]
        dec = _aes128_decrypt_block(blk, rk)
        out += bytes(a^b for a, b in zip(dec, prev))
        prev = blk
    pad = out[-1]
    return bytes(out[:-pad]) if 1 <= pad <= 16 else bytes(out)


# ── Minimal ZIP reader (no stdlib zipfile needed) ─────────────────────────────

def _zip_entries(buf: bytes):
    eocd = -1
    for i in range(len(buf) - 22, -1, -1):
        if buf[i:i+4] == b'PK\x05\x06':
            eocd = i; break
    if eocd < 0:
        raise ValueError("No EOCD record in ZIP")
    cd_off = struct.unpack_from('<I', buf, eocd+16)[0]
    num    = struct.unpack_from('<H', buf, eocd+10)[0]
    entries = []
    pos = cd_off
    for _ in range(num):
        if buf[pos:pos+4] != b'PK\x01\x02': break
        comp   = struct.unpack_from('<H', buf, pos+10)[0]
        csz    = struct.unpack_from('<I', buf, pos+20)[0]
        usz    = struct.unpack_from('<I', buf, pos+24)[0]
        fn_len = struct.unpack_from('<H', buf, pos+28)[0]
        ex_len = struct.unpack_from('<H', buf, pos+30)[0]
        cm_len = struct.unpack_from('<H', buf, pos+32)[0]
        lh_off = struct.unpack_from('<I', buf, pos+42)[0]
        name   = buf[pos+46:pos+46+fn_len].decode(errors='replace')
        entries.append((name, comp, csz, usz, lh_off))
        pos += 46 + fn_len + ex_len + cm_len
    return entries

def _extract_entry(buf: bytes, entry) -> bytes:
    name, comp, csz, usz, lh_off = entry
    fn_len = struct.unpack_from('<H', buf, lh_off+26)[0]
    ex_len = struct.unpack_from('<H', buf, lh_off+28)[0]
    data_off = lh_off + 30 + fn_len + ex_len
    raw = buf[data_off:data_off+csz]
    if comp == 0:
        return raw
    return zlib.decompress(raw, -15)


# ── Battle parser ─────────────────────────────────────────────────────────────
#
# Four complementary strategies cover all known SF3 battle definition patterns:
#
# Strategy 1 – Direct scan (all scripts/features/ + scripts/z_utils/ JS files)
#   • Long window (2000 chars): ID → DefaultTemplate → RoundsToWin
#     Covers: event battles, chapter missions, adventure, raid, etc.
#   • Short window (500 chars): ID → RoundsToWin (no template required)
#     Covers: faction-war constructor patterns (new SideBattleArchetype)
#
# Strategy 2 – Cross-file skin intro_battles
#   • *_intro_battles.js defines intro_KEY → RoundsToWin (per narrative battle)
#   • Companion *_skin.js maps intro_KEY → literal battle ID
#   • Join on key name to produce (ID, rounds) pairs
#   Covers: frost_skin, circus_skin, ling_skin, etc.
#
# Strategy 3 – __assign chain resolution
#   • Find var BASE = {..., RoundsToWin: N} definitions across the file
#   • For PROP: __assign({...}, BASE), build PROP → rounds (1-level)
#   • For __assign({ID: N, ...}, TEMPLATE.PROP), resolve 2-level chain
#   Covers: summer_fest and other template-spread event battles
#
# Strategy 4 – Skeleton/meta battles (cross-file: skeleton JS + skin JS)
#   • skeleton_danger.js / skeleton_incremental.js define key → RoundsToWin
#     via tight  key:__assign({..., RoundsToWin:N, ...}, ...)  blocks
#   • Each *_skin.js maps those same keys to concrete IDs via
#     prepareArchBattle(template, {ID: NNNNN, ...}) calls
#   • For keys without explicit RoundsToWin (lockers → 1; classic fights →
#     roundCountSettings.roundsToWins[0] or ceil(N_fights/2))
#   Covers: level_1_as3 (3101111), level_2_*, level_3_*, level_4_* across all
#           skins; incremental survival/boss battles in cosmetic, british, etc.


# ── Strategy 4 helpers ────────────────────────────────────────────────────────

_LOCKER_KEYS = frozenset({
    'level_2_locker', 'level_3_locker', 'level_4_locker',
})


def _parse_skeleton_key_rounds(text: str) -> dict:
    """Return {key: RoundsToWin} for keys with an EXPLICIT value in a skeleton
    JS file.  Uses a tight per-key window (next key's __assign start) so we
    don't bleed RoundsToWin values across keys."""
    bad = frozenset(('var', 'function', 'return', 'if', 'else', 'new'))
    positions = [
        (m.group(1), m.start())
        for m in re.finditer(r'(\w+)\s*:\s*__assign\s*\(', text)
        if m.group(1) not in bad
    ]
    result = {}
    for i, (key, pos) in enumerate(positions):
        end = positions[i + 1][1] if i + 1 < len(positions) else len(text)
        chunk = text[pos:end]
        rm = re.search(r'\bRoundsToWin\s*:\s*(\d+)', chunk)
        if rm:
            result[key] = int(rm.group(1))
    return result


def _infer_rounds_from_skin(skin_text: str, key: str):
    """Infer RoundsToWin for a skeleton key that has no explicit value in the
    skeleton JS file.  Reads the skin data block for that key to find:
      • roundCountSettings.roundsToWins → if multiple values, return the full list
        (each element is the rounds-to-win for that sub-battle index, e.g. [2,2,3])
        if a single value, return it as int.
      • classic fights count N → ceil(N / 2) as int
    Falls back to 2 (int).

    Some skins use a different key name in the skin data block than in the
    prepareArchBattle call (e.g. 'level_3_as3_boss' vs 'level3as3boss' or
    'level_3As3Boss').  Both forms are tried by normalising underscores and case."""
    # First try the exact key
    m = re.search(re.escape(key) + r'\s*:\s*\{', skin_text)
    if not m:
        # Try with underscores stripped + case-insensitive
        normalised = key.replace('_', '').lower()
        m = re.search(re.escape(normalised) + r'\s*:\s*\{', skin_text, re.IGNORECASE)
    if not m:
        return 2
    chunk = skin_text[m.start(): min(len(skin_text), m.start() + 1500)]
    rcs = re.search(r'roundsToWins\s*:\s*\[([^\]]+)\]', chunk)
    if rcs:
        vals = [int(x.strip()) for x in rcs.group(1).split(',') if x.strip().isdigit()]
        if len(vals) > 1:
            return vals   # Multi-fight: full array, one entry per sub-battle
        elif len(vals) == 1:
            return vals[0]
        return 2
    fights_m = re.search(r'fights\s*:\s*\{([^}]+)\}', chunk)
    if fights_m:
        n = len(re.findall(r'"\d+"', fights_m.group(1)))
        if n > 0:
            return (n + 1) // 2
    return 2


def _parse_skin_skeleton_battles(skin_text: str, skeleton_rounds: dict) -> list:
    """Return (battle_id, rounds) pairs for all non-intro_ keys in a skin file.
    Rounds come from skeleton_rounds (explicit) or from skin data inference.
    intro_* keys are intentionally skipped (handled by Strategy 2)."""
    results = []
    pat = re.compile(
        r'(\w+)\s*:\s*prepareArchBattle\s*\([^,]+,\s*\{[^}]*?ID\s*:\s*(\d{5,})'
    )
    for m in pat.finditer(skin_text):
        key, bid_str = m.group(1), m.group(2)
        if key.startswith('intro'):
            continue
        bid = int(bid_str)
        if key in _LOCKER_KEYS:
            results.append((bid, 1))
        elif key in skeleton_rounds:
            results.append((bid, skeleton_rounds[key]))
        else:
            rounds = _infer_rounds_from_skin(skin_text, key)
            results.append((bid, rounds))
    return results


def _parse_direct(text: str) -> list:
    results = []
    id_re = re.compile(r'\bID\s*:\s*(\d{5,})')
    for m in id_re.finditer(text):
        bid = int(m.group(1))
        # Long window: require DefaultTemplate to filter hub/locker non-combat battles
        ctx = text[m.start():min(len(text), m.start() + 2000)]
        if re.search(r'DefaultTemplate\s*:', ctx):
            rm = re.search(r'RoundsToWin\s*:\s*(\d+)', ctx)
            results.append((bid, int(rm.group(1)) if rm else 3))
            continue
        # Short window: ID and RoundsToWin must be very close (constructor patterns)
        ctx_short = text[m.start():min(len(text), m.start() + 500)]
        rm = re.search(r'RoundsToWin\s*:\s*(\d+)', ctx_short)
        if rm:
            results.append((bid, int(rm.group(1))))
    return results


def _parse_skin_intro(skin_text: str, intro_text: str) -> list:
    # From intro_battles file: intro_KEY -> rounds (3000-char forward window)
    key_rounds = {}
    for m in re.finditer(r'(intro_\w+)\s*:', intro_text):
        key = m.group(1)
        if key in key_rounds:
            continue
        snippet = intro_text[m.start():m.start() + 3000]
        rm = re.search(r'RoundsToWin\s*:\s*(\d+)', snippet)
        if rm:
            key_rounds[key] = int(rm.group(1))
    # From skin file: intro_KEY -> literal battle ID
    key_id = {}
    for m in re.finditer(
            r'(intro_\w+)\s*:\s*prepareArchBattle\s*\([^{]+\{[^}]*ID\s*:\s*(\d{5,})',
            skin_text):
        key = m.group(1)
        if key not in key_id:
            key_id[key] = int(m.group(2))
    return [(key_id[k], r) for k, r in key_rounds.items() if k in key_id]


def _parse_assign_chain(text: str) -> list:
    # Build direct var -> rounds map (first 3000 chars of each var definition)
    var_rounds = {}
    for vm in re.finditer(r'\bvar\s+(\w+)\s*=', text):
        varname = vm.group(1)
        snippet = text[vm.start():min(len(text), vm.start() + 3000)]
        rm = re.search(r'RoundsToWin\s*:\s*(\d+)', snippet)
        if rm:
            var_rounds[varname] = int(rm.group(1))

    # Build 1-level property map: PROP -> rounds (for TEMPLATE.PROP lookups)
    prop_rounds = {}
    for m in re.finditer(r'(\w+)\s*:\s*__assign\s*\(', text):
        prop = m.group(1)
        snippet = text[m.start():min(len(text), m.start() + 5000)]
        tail = re.search(r'\},\s*([\w.]+)\s*\)', snippet)
        if tail:
            base_root = tail.group(1).split('.')[0]
            if base_root in var_rounds:
                prop_rounds[prop] = var_rounds[base_root]

    results = []
    id_re = re.compile(r'\bID\s*:\s*(\d{5,})')
    for m in id_re.finditer(text):
        bid = int(m.group(1))
        fwd = text[m.start():min(len(text), m.start() + 1000)]
        tail = re.search(r'\},\s*([\w.]+)\s*\)', fwd)
        if not tail:
            continue
        chain = tail.group(1)
        parts = chain.split('.')
        root, prop = parts[0], parts[1] if len(parts) > 1 else None
        if root in var_rounds:
            results.append((bid, var_rounds[root]))
        elif prop and prop in prop_rounds:
            results.append((bid, prop_rounds[prop]))
    return results


def parse_all_battles(config_zip_bytes: bytes) -> dict:
    """Returns {battle_id_str: rounds_int} from the decrypted inner config ZIP."""
    entries = _zip_entries(config_zip_bytes)
    all_js = [e for e in entries if e[0].endswith('.js')
              and (e[0].startswith('scripts/features/')
                   or e[0].startswith('scripts/z_utils/'))]
    print(f"    Scanning {len(all_js)} JS files across features + z_utils")

    seen = {}

    # ── Strategy 2: cross-file skin intro_battles (highest priority) ────────────
    skin_dirs = {}
    for e in all_js:
        name = e[0]
        if 'templates/skins/' not in name:
            continue
        parts = name.split('/')
        dir_key = '/'.join(parts[:-1])
        fname = parts[-1]
        if dir_key not in skin_dirs:
            skin_dirs[dir_key] = {}
        if fname.endswith('_skin.js'):
            skin_dirs[dir_key]['skin'] = e
        elif fname.endswith('_intro_battles.js'):
            skin_dirs[dir_key]['intro'] = e

    for dir_key, fmap in skin_dirs.items():
        if 'skin' not in fmap or 'intro' not in fmap:
            continue
        try:
            skin_text  = _extract_entry(config_zip_bytes, fmap['skin']).decode('utf-8', errors='replace')
            intro_text = _extract_entry(config_zip_bytes, fmap['intro']).decode('utf-8', errors='replace')
            for bid, rnd in _parse_skin_intro(skin_text, intro_text):
                if bid not in seen:
                    seen[bid] = rnd
        except Exception as ex:
            print(f"    [!] skin_intro error in {dir_key}: {ex}")

    # ── Strategies 1 + 3: scan every feature/util JS file ───────────────────────
    for ef in all_js:
        try:
            text = _extract_entry(config_zip_bytes, ef).decode('utf-8', errors='replace')
            for bid, rnd in _parse_direct(text):
                if bid not in seen:
                    seen[bid] = rnd
            for bid, rnd in _parse_assign_chain(text):
                if bid not in seen:
                    seen[bid] = rnd
        except Exception as ex:
            print(f"    [!] Error parsing {ef[0]}: {ex}")

    # ── Strategy 4: skeleton/meta battles (lowest priority, fills gaps) ──────────
    # Build combined key→rounds map from skeleton_danger.js and
    # skeleton_incremental.js, then cross-reference with each skin file.
    skeleton_rounds: dict = {}
    skeleton_files = [
        e for e in entries
        if e[0].startswith('scripts/features/events/templates/skeletons/')
        and e[0].endswith('.js')
    ]
    for sf in skeleton_files:
        try:
            skel_text = _extract_entry(config_zip_bytes, sf).decode('utf-8', errors='replace')
            skeleton_rounds.update(_parse_skeleton_key_rounds(skel_text))
        except Exception as ex:
            print(f"    [!] skeleton parse error {sf[0]}: {ex}")

    if skeleton_rounds:
        print(f"    Skeleton key rounds loaded: {len(skeleton_rounds)} explicit keys")
        for dir_key, fmap in skin_dirs.items():
            if 'skin' not in fmap:
                continue
            try:
                skin_text = _extract_entry(config_zip_bytes, fmap['skin']).decode('utf-8', errors='replace')
                for bid, rnd in _parse_skin_skeleton_battles(skin_text, skeleton_rounds):
                    if bid not in seen:
                        seen[bid] = rnd
            except Exception as ex:
                print(f"    [!] skin_skeleton error in {dir_key}: {ex}")

    print(f"    Found {len(seen)} battle entries")
    return {str(k): v for k, v in sorted(seen.items())}


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Extract SF3 battle IDs to data/battles.json")
    parser.add_argument(
        "--local-zip",
        metavar="PATH",
        help=(
            "Path to a decrypted inner-config ZIP file.  "
            "When provided, steps 1-3 (balance fetch / download / decrypt) are "
            "skipped and the ZIP is used directly."
        ),
    )
    args = parser.parse_args()

    if args.local_zip:
        # ── Local-zip mode: skip network + decrypt steps ──────────────────────
        zip_path = os.path.abspath(args.local_zip)
        if not os.path.isfile(zip_path):
            sys.exit(f"ERROR: --local-zip file not found: {zip_path}")
        print(f"[local] Reading inner-config ZIP: {zip_path}")
        with open(zip_path, "rb") as f:
            inner_zip = f.read()
        print(f"        {len(inner_zip):,} bytes")

        # Try to read version from version.json inside the zip
        version_str = "local"
        try:
            entries = _zip_entries(inner_zip)
            ver_entries = [e for e in entries if e[0].endswith('version.json')]
            if ver_entries:
                ver_data = json.loads(_extract_entry(inner_zip, ver_entries[0]).decode())
                version_str = ver_data.get("version", ver_data.get("Version", "local"))
        except Exception:
            pass
        print(f"        Version : {version_str}")

    else:
        # ── Network mode: fetch balance, download, decrypt ────────────────────
        rand = random.randint(1000, 9999)
        url = BALANCE_URL.format(rand=rand)
        print(f"[1] Fetching balance: {url}")
        req = urllib.request.Request(url, headers={"User-Agent": "UnityPlayer/2022.3"})
        with urllib.request.urlopen(req, timeout=15) as r:
            balance = json.loads(r.read())

        version_str = balance["version"]["cur"]
        config_url  = balance["version"]["url"]
        print(f"    Version : {version_str}")
        print(f"    ZIP URL : {config_url}")

        print(f"[2] Downloading config archive…")
        req2 = urllib.request.Request(config_url, headers={"User-Agent": "UnityPlayer/2022.3"})
        with urllib.request.urlopen(req2, timeout=60) as r:
            outer_zip = r.read()
        print(f"    Downloaded {len(outer_zip):,} bytes")

        print(f"[3] Finding + decrypting .enc entry…")
        entries = _zip_entries(outer_zip)
        enc_entries = [e for e in entries if e[0].endswith('.enc')]
        if not enc_entries:
            sys.exit("ERROR: no .enc file found in outer ZIP")
        enc_entry = min(enc_entries, key=lambda e: len(e[0].split('/')[-1]))
        print(f"    Entry   : {enc_entry[0]}")
        enc_data = _extract_entry(outer_zip, enc_entry)
        inner_zip = aes128_cbc_decrypt(enc_data, AES_KEY, AES_IV)
        print(f"    Decrypted to {len(inner_zip):,} bytes")

    print(f"[4] Parsing battles from inner config ZIP…")
    battles = parse_all_battles(inner_zip)
    print(f"    Found {len(battles)} battle entries")

    out = {
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "version":   version_str,
        "battles":   battles,
    }

    out_path = os.path.normpath(OUT_FILE)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(out, f, indent=2)
    print(f"[5] Written → {out_path}  ({len(battles)} battles)")

    if battles:
        sample = list(battles.items())[:5]
        for bid, r in sample:
            print(f"    id={bid}  rounds={r}")


if __name__ == "__main__":
    main()
