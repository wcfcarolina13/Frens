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

Notes:
- Bot uses its own inventory only; reports how many were crafted if short on materials. For tools beyond basic wood/stone, it will list available materials; rerun with preferred material (e.g., `/bot craft axe 1 iron`).
- Placement ignores snow layers/blocks. For place, bot aligns to your look ray; retries up to 10 times before saying “I can’t reach that spot.”
