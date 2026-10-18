# Field phases 0-6ai in the sandbox: Frens 1.1.270 (2026-10-18), IN PROGRESS

Stopped early: usage limit. One session ran (save `Sweep Souls`, copy of `New World`, commands on, Piper copied from Nirn's config, Ollama llama3.1:8b through `tools/sandbox/ollama_tap.py` on 11435). Nothing ticked in the checklist yet. Open counts unchanged: 267 open, 95 done.

## Verdicts so far (S1, 16:49-16:52)

| Line | Verdict | Evidence |
|---|---|---|
| L23 | PASS | S1 `[16:49:57] [souls] runtime started; masterEnabled=true settingsValid=true` (the old `-PaiEnabled` line is gone since 1.1.250, by design) |
| L31 | PASS | S1 `/bot soul enable Jake` then `Bob`: chat "Jake is now speaking as Jake." and "Bob is now speaking as Bob." (16:50:29-31); `/bot soul status` shows `Bob ... profile=frens:bob`. The pre-1.1.184 `frens:jake` binding does not exist in a fresh sandbox, so the legacy rebind path was not exercised. |
| L35 | PASS | S1 16:50:33-39: "Companion banter set to on", "Companion local chat set to on", "Memory digest is ON. Runs at each Minecraft day rollover for soul-bound bots.", "Soul voice enabled." |
| L39 | PASS | S1 Piper (Nirn's installed Piper folder copied into the sandbox config, read-only): `[16:50:40] [souls] tts engine started pid=17837 model=en_US-lessac-medium.onnx`, no `tts engine unavailable`. Engine set in config, not through the Eng... button. |
| L43 | PASS | S1 16:50:54-58: soul status (`system=on settingsValid=true ready=true provider=ollama model=llama3.1:8b providerHealthy=true queueDepth=0`), banter status (`Idle: last verdict: vetoed:cooldown, cooldown: 123s remaining. Active: ... no evaluation yet`), local status, all without error. |
| L83 | PASS | S1 `[16:51:13] [souls] banter routingId=24e11327... outcome=fired rosterCount=2`, playback 4/4, Jake and Bob each spoke twice. |
| L87 | PASS | S1 routingId 24e11327: each `scene-playback ... speaker=` UUID matches the chat name (Jake 2af3f0a5, Bob 6a00845c), no dropped unknown speaker. |
| L90 | PASS | S1 routingId 24e11327: speakers Jake, Bob, Jake, Bob. |
| L98 | PASS | S1 `/bot soul banter now` at 16:51:11, `[16:51:13] banter lane=IDLE ... outcome=fired`; banter status lists both the Idle and Active lanes. |

Also seen, not yet written up: 6o L826 prewarm (`tts prewarm voices=2` and engine start at 16:50:40, before the first scene), 6k L625 peer stance lines for both bots at 16:51:47 (mind.json not read yet), Phase 5 L183 voice list (lessac and ryan installed, both bots on the global voice), no `ollama not reachable` line (L27). Note: `voiced=true` playback but 0 `tts correlationId` lines, check before calling L66/Phase 6 voice items.

## Buckets decided from the text (not run)

```python
# line -> (bucket, note). T = sandbox-testable; MP = needs a second player/LAN/server; EAR = subjective listen/judge;
# NIRN = Nirn-only data; DL = mod downloads from the network; J = NEEDS-JUDGEMENT (skip); FORT = fortify village run
B = {}
def s(lines, b, note=""):
    for l in lines: B[l] = (b, note)
s([66], "EAR", "first audible word needs ears; synthMs recorded only")
s([119], "EAR", "design call, Bradley's judgement")
s([122], "EAR", "frame stall is reported by the player")
s([138], "MP", "needs a second player as the bystander")
s([186, 199, 202, 206], "DL", "the mod downloads Piper/voices/Pocket from the network; Claude does not start downloads unasked")
s([210], "EAR", "subjective voice-quality verdict")
s([197], "EAR", "Dreamsleeve clone comparison by ear")
s([278, 283, 287, 291, 296], "MP", "needs host H and guest G")
s([487, 1175, 1407, 1431, 1470, 1521], "J", "NEEDS-JUDGEMENT in .superpowers/field-triage/obsolete.md, unanswered")
s([517, 522, 528, 532, 536, 541, 553, 560, 564, 569, 573], "FORT", "fortify village build")
s([618, 664], "NIRN", "needs pre-1.1.212/1.1.213 mind.json files; the sandbox has none and Nirn migrated long ago")
s([932, 975, 1192, 1200, 1207, 1250, 1259, 1369, 1373, 1377, 1380, 1383, 1395, 1399, 1403, 1422, 1427], "MP", "needs a second player, LAN guest or server op")
s([1239], "EAR", "needs `ollama rm` of a model on the shared Ollama; Bradley's call")
s([1333], "NIRN", "Bradley's own base on Nirn")
```

## Left
Everything else in the 239 open items of phases 0-6ai. Plan: finish the souls session (Phases 1-4, 6, 6j-6o, 6q), then a gameplay session on a copy of Wall Test (6c-6g, 6r-6u, 6aa-6ac, 6ai), then short relaunches (6x, 6y, 6ag, 6v, 6z), fortify last. Phases 7-10 are out of scope.
