# Crafting/Placing Quick Reference

- Command: `/bot craft <item> [amount]` — crafts as many as resources allow (amount optional; defaults to 1).
- Command: `/bot place <item> [count]` — look at a block face first; places up to count (default 1).

Accepted item keys (case-insensitive):
- `crafting_table`
- `sticks` / `stick`
- `axe`
- `shovel`
- `pickaxe`
- `hoe`
- `sword`
- `shield`
- `bucket`
- `shears`
- `furnace`
- `chest` (places successive chests adjacent if count > 1)
- `bed` / `beds` (requires 3 matching wool + 3 planks per bed; picks a craftable color)

Notes:
- Bot primarily uses its own inventory; reports how many were crafted if short on materials. For tools beyond basic wood/stone, rerun with preferred material (e.g., `/bot craft axe 1 iron`).
- 3x3 crafts (e.g., `bed`, `chest`, `furnace`, `bucket`, tools) require a crafting table; if none is reachable, the bot will place one from inventory or craft+place one if it can.
- When short on basic inputs, the bot will try to pull materials from nearby chests (within ~10 blocks) before giving up.
- Placement ignores snow layers/blocks. For place, bot aligns to your look ray; retries up to 10 times before saying “I can’t reach that spot.”
