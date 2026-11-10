# Frens wiki

Frens adds companions ("frens") to Minecraft Java: full fake players that follow you, gather, farm, mine, build and defend, with their own inventory and stats. Everything works with no AI model. Talking back in their own words (Souls, through a local Ollama model) is optional.

Download: [Modrinth](https://modrinth.com/mod/mcfrens). Source and issues: [GitHub](https://github.com/wcfcarolina13/Frens).

## Pages

- [Getting started](Getting-Started.md): install, pick a world mode, spawn your first fren.
- [FAQ](FAQ.md): common questions and fixes.
- [Work loops](Work-Loops.md): frens that go back to the same job every morning.
- [Fast travel](Fast-Travel.md): what lets a fren skip the walk, how long it takes and what it costs.
- [Bases and home](Bases-and-Home.md): bases, beds and the preferred home, and where a fren goes at sunset.
- [Protected zones](Protected-Zones.md): where frens won't break blocks or gather, and why.
- [Storage and chests](Storage.md): putting items in chests and taking them out, and when frens ask before taking your supplies.
- [Guard and patrol](Guard-and-Patrol.md): guard, patrol and stay, what a fren fights, and how the orders end.
- [Farming](Farming.md): building a farm, planting and harvesting, and where the crops go.
- [Hunting and cooking](Hunting.md): starting a hunt, what frens won't hunt, loot, how a hunt ends and cooking.
- [Fortifying a village](Fortify.md): the wall, towers, gate and moat, materials, and looking after a saved wall.
- [Talking with frens (AI chat)](AI-Chat.md): setting up Ollama and Soul Chat, talking to frens, memory and what to do when they don't answer.
- [Multiplayer and ownership](Multiplayer.md): owners, operators and the host, sharing bases and walls, and dedicated servers.
- [Spells](Spells.md): every spell, what unlocks it and what it costs, and the Wizard's Tome.
- [In-game Guide](Guide.md): every card from the Guide screen (press ] in game), grouped by topic.
- [Companion questing guide](https://github.com/wcfcarolina13/Frens/blob/main/COMPANION_QUESTING_GUIDE.md): how recruiting works in Questing mode.
- [Crafting cheatsheet](https://github.com/wcfcarolina13/Frens/blob/main/CRAFTING_CHEATSHEET.md)
- [README](https://github.com/wcfcarolina13/Frens/blob/main/README.md): install and requirements.

## How these pages are made

The `Guide*.md` pages are generated from `src/main/resources/assets/frens/guide/topics.json`, the same file the in-game Guide reads, so the two always match. To change a Guide card, edit that file, run `./gradlew wikiExport`, and commit both. `GuideWikiExportTest` fails if they differ.

Pages without the `Guide` prefix, like this one, are written by hand.

The site at https://wcfcarolina13.github.io/Frens/ is built from these files by `.github/workflows/wiki-pages.yml` on each push to main (`mkdocs.yml` holds the navigation). A new page needs a line in that file's `nav`.
