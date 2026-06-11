---
name: SF3 finish_fight intercept root cause
description: Why HAMMERSCALE's mid-fight injection wins server-side but SF3 game never shows win screen — and the correct MITM fix
---

## Root Cause
Injection fires mid-fight (game engine in "playing" state). Server processes it and returns win on the injection's TCP connection. readerLoop forwards the win response to the SF3 app via TUN. SF3 game receives it but IGNORES it — the game's state machine is busy playing, not in "waiting for server response" state.

## Evidence (capture user_86/server_86)
- user_86.bin: ctr=157828789, event_battle_finish_fight, field[4]=3, field[13]={field[2]=29} → server_86.bin: 11516B WIN (fight_counter=61050, 27KB rewards). Server CREDITED the win.
- user_106.bin: real app's own finish_fight at ctr=27, field[13]={field[2]=18} → error "Out of attempts" (battle already consumed).

## SF3 packet facts (confirmed from captures)
- finish_fight ALWAYS arrives in one TCP segment (57-60B, type 0x01 small frame)
- Winning field[13][2] = 29. Losing = 25. Unknown exit = 18.
- Real app uses field[4]=3, field[7]=1 (not field[4]=1 like the Python script)
- Python script's make_finish_fight_win() uses counter=0 (broken, never called from run_fight)

## The fix: ARM-WIN intercept-and-replace
Instead of injecting mid-fight:
1. User taps "ARM WIN" → sets interceptArmed flag in TcpHandler
2. Game plays normally until fight ends (any result)
3. TcpHandler.handleAck intercepts the outbound finish_fight before queuing
4. Replaces payload with PacketInjector.buildFinishFight(battleId, SAME_COUNTER)
5. Server responds on the connection the game was waiting on → game shows WIN screen

## Implementation
- GameProtocolParser.tryExtractFinishFight(data) → Pair(battleId, counter)?
- TcpHandler.interceptArmed: AtomicBoolean + armIntercept() + disarmIntercept()
- handleAck: if armed → tryExtractFinishFight → build replacement → send replacement, not original
- TrafficVpnService.armIntercept() / disarmIntercept() delegates to tcpHandler
- OverlayService: interceptIsArmed state, button shows "ARM WIN" / "⚡ ARMED — play to fight end"
