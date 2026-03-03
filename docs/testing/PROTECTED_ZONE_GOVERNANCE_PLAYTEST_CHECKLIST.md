# Protected Zone Governance + Ownership v2 — Playtest Checklist

Use this checklist to validate protected-zone commands, permission behavior, persistence, UI flows, and docs discoverability.

## Core Command Flow

- [ ] **Create/list/remove basics**
  - `/bot zone protect <label>`
  - `/bot zone list`
  - `/bot zone remove <label>`
  - Confirm labels save/remove correctly and list updates immediately.

- [ ] **Mode switching works**
  - `/bot zone mode <label> owner_only`
  - `/bot zone mode <label> allowlist`
  - `/bot zone mode <label> public`
  - Confirm behavior changes live (no restart needed).

- [ ] **Permit/Revoke flow**
  - `/bot zone permit <label> <owner>`
  - `/bot zone revoke <label> <owner>`
  - Test `<owner>` as:
    - player name
    - UUID
    - bot-owner alias
  - Confirm duplicate permits and revoke-not-present cases return sane feedback.

## Authorization & Enforcement

- [ ] **Authorization behavior by mode**
  - `owner_only`: only owner can act in zone.
  - `allowlist`: owner + explicitly permitted owners can act.
  - `public`: everyone can act.
  - Verify with at least 2 players + 1 bot-owner context.

- [ ] **Protected-zone enforcement**
  - Non-owner cannot place/break inside another owner’s protected zone (when not allowed).
  - Allowed owner can place/break in allowlist/public as expected.

- [ ] **Fort wall/interior ownership enforcement**
  - Claimed fort interiors respect ownership policy.
  - Foreign owners blocked when policy should deny.
  - Owner and permitted users still function normally.

- [ ] **Regression checks for previous over-blocking fix**
  - Owner bot should still mutate blocks in its own protected territory during recovery flows (no false deny).
  - Spot-check:
    - Return-base stuck recovery behavior
    - Mounted leaf clearing behavior

## Output, Persistence, and UX

- [ ] **Zone list output quality**
  - `/bot zone list` shows:
    - zone label
    - access mode
    - permit count
  - Confirm counts change after permit/revoke.

- [ ] **Persistence after restart**
  - Restart server/world.
  - Confirm zone data persists:
    - mode
    - allowed owners
    - ownership metadata
  - Re-test one protected action after restart.

- [ ] **UI checks (Base Manager UI if applicable)**
  - Permit/Revoke buttons work end-to-end.
  - Claim/Unclaim wall controls apply correctly.
  - Owner display is accurate.

- [ ] **Error handling / UX sanity**
  - Invalid mode value shows clear message.
  - Unknown owner in permit/revoke shows clear message.
  - Unauthorized commands show clear owner/admin requirement message.

## Docs Discoverability

- [ ] **Docs consistency checks**
  - `README.md` Protected Zones section matches actual commands.
  - In-game guide includes **Protected Zones (Access Control)** topic.
  - `COMPANION_QUESTING_GUIDE.md` quick tips are accurate.
  - `DOCS_INDEX.md` quick link jumps to the quick tips section.

## Suggested Quick Matrix (Fast Coverage)

- [ ] Player A owner + Player B non-owner + Player C allowlisted
- [ ] Same zone through modes: `owner_only -> allowlist -> public`
- [ ] Verify expected break/place behavior for A/B/C at each mode
