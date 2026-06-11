---
name: SF3 extract_battles skeleton strategy
description: How skeleton/meta battle IDs (level_1_as3, level_2_su7_hard, etc.) are parsed in extract_battles.py Strategy 4
---

## Problem
Skin files like `frost_skin.js` define battle IDs via `prepareArchBattle(template, {ID: NNNNN})` for keys like `level_1_as3`, `level_2_su7_hard`, etc. These are NOT directly scanned by Strategies 1-3 (no inline `RoundsToWin` near the ID in those calls).

## Solution: Strategy 4

**Step 1 — skeleton JS files** (`scripts/features/events/templates/skeletons/skeleton_danger.js`, `skeleton_incremental.js`)  
These define `key:__assign({..., RoundsToWin:N, ...}, ...)` blocks for each key type.  
Use **tight per-key windows** (end at next `__assign` start) to avoid bleeding across keys.

Explicit rounds from skeleton_danger.js:
- `level_2_su7_hard: 7`, `level_3_su6: 6`, `level_4_su5: 5`
- `level_4_solo_boss: 3`, `level_4_solo_boss_hard: 3`
- Locker keys (`level_2_locker`, `level_3_locker`, `level_4_locker`): hardcoded → 1

No explicit round in skeleton JS:
- `level_1_as3`, `level_2_as4`, `level_3_as3_boss`: infer from skin data

**Step 2 — skin data inference** (for keys without explicit rounds)  
Look up key's data block in the skin file:
- `roundCountSettings.roundsToWins:[N,...]` present → use `N` (e.g. `level_2_as4` → 2)
- No roundCountSettings → count classic fights → `(N+1)//2` (e.g. `level_1_as3`: 3 fights → 2 ✓)

**Step 3 — ID mapping**  
Each `*_skin.js` has `KEY:prepareArchBattle(template, {ID:NNNNN})`.  
Skip any key starting with `intro` (already handled by Strategy 2).

**Why:** `prepareArchBattle` copies the template into a new object and overrides only ID/icon/section/map. RoundsToWin is not overridden, so it stays in the skeleton JS definition, NOT near the ID.

**Result:** 1033 total battles (up from ~800 without Strategy 4) covering all 13 skins × 11 skeleton-danger keys + incremental keys.

## skeleton_incremental.js explicit rounds
`entry_survival:8`, `survival_1:7`, `survival_2:6`, `survival_3:6`, `mini_boss:3`,
`hard_battle:3`, `idle_*:1`, `repeatable:1`, `final_boss_*:3`.
`ascension_1`, `ascension_2`: no explicit → inferred from skin data.
