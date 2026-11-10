# Talking with frens (AI chat)

Frens work without any AI. Commands, buttons and the Guide all work on their own. If you also want to chat with your frens in your own words, Frens can use a language model that runs on your own computer through [Ollama](https://ollama.com). This feature is called **Soul Chat**. This page explains how to set it up, how to talk to a fren, what a fren remembers and what to do when it doesn't answer.

The short version is the Guide card [Set up AI companions](Guide-Basics.md#set-up-ai-companions).

## What you need

- **Ollama**, installed and running. Frens can't install it for you. Get it from [ollama.com/download](https://ollama.com/download).
- **A model.** There is no default, so you have to pick one. The setup screen offers three:

| Model | Download | Memory it needs | Notes |
|---|---|---|---|
| `llama3.1:8b` | 4.6 GB | about 12 GB | Best replies, slowest. The replies were tuned on this one. |
| `llama3.2:3b` | 1.9 GB | about 6 GB | Faster, decent replies |
| `llama3.2:1b` | 1.3 GB | about 4 GB | Fastest, simplest replies |

The memory figure is on top of what Minecraft itself uses. Any other model you have installed in Ollama works too.

### On a server

The AI runs where the world runs. In singleplayer that is your computer. On a server, Ollama has to run on the **server machine**, and the server's operator sets it up. The setup screen marks those steps "(on the server machine)", and you can only pick a model that is already installed there.

## Setting it up

Open the Guide (press **]**) and press **AI setup**, or open Bot Controls and press **Setup…** next to Soul Chat. The **Set up AI companions** checklist shows each step as done (✔), to do (✖) or optional (•):

1. **Ollama installed.** **Get Ollama** opens the download page. Start Ollama, then press **Refresh**.
2. **AI model downloaded.** **Choose model…** opens the model screen. It shows whether Ollama is running, how much memory your computer has, and warns you ("Download ⚠") when a model may be too big. In singleplayer it downloads the model for you. On a server, the operator runs `ollama pull <model>` on the server machine.
3. **Soul Chat turned on.** **Turn on** switches it on for the whole world. Only an operator or the host can do this.
4. **Companion enabled.** **Enable** turns Soul Chat on for that fren. **Pick…** chooses its persona (and, for operators or the host, its voice).
5. **Test chat.** **Say hi** fills in "Lia, can you hear me?" for you to send.
6. **Voice (optional).** Spoken replies. See [Voice](#voice).

The first reply after picking a model can take a while, because Ollama has to load it.

### By command

| Command | What it does | Who |
|---|---|---|
| `/bot soul system on` or `off` | Soul Chat on or off for the whole world | Operator or host |
| `/bot soul enable <bot>` | Turns Soul Chat on for that fren | Operator or host |
| `/bot soul disable <bot>` | Turns it off for that fren. Its memory is kept. | Operator or host |
| `/bot soul model <name>` | Picks the model by its Ollama name | Operator or host |
| `/bot soul status [bot]` | Shows whether everything is ready | Operator or host |

These are typed `/bot` commands, so on a server they need an operator or the host. An owner who isn't an operator uses **Enable** and **Pick…** in the setup checklist.

### A different Ollama address

Frens looks for Ollama at `http://127.0.0.1:11434`, the address Ollama uses by default. To use Ollama on another machine, set `ollamaBaseUrl` in `config/settings.json5` in the game folder while the game is closed. Any `http` or `https` address works, including one behind a proxy. There is no in-game setting for this.

## Talking to a fren

Type in normal chat, with the fren's name in the line:

- `Lia, what are you up to?`
- `how's the mine going, Lia?`

`/msg Lia hello` works too. The reply comes back only to you, as "Lia: …". When you are the only player online, for 30 seconds after a fren answers your next lines carry on the conversation without the name. Otherwise, name the fren each time.

### Several frens at once

Start with **Bots,** or **All bots,**, or name a few: `Lia and Wren, who wants to go mining?`. Up to 4 frens within 32 blocks of you join in, nearest first. Each says at most 2 lines. When you are the only player online, "everyone", "guys", "y'all" and "you two" work too.

### Who can talk to which fren

Only a fren's owner, an operator or the host. Anyone else gets "Lia's private conversation is available only to their owner or an operator."

You have to be within 32 blocks. From further away you need a way to reach the fren: both of you holding an Eye of Ender or a Goat Horn, either of you carrying a Wizard's Tome, or either of you standing within 4 blocks of an enchanting table. Otherwise: "You cannot reach Lia from here."

### Questions about the mod

Ask a fren how to do something, such as "Lia, how do I fortify a village?", and it answers from the Guide. This works even when Soul Chat is off or Ollama isn't running.

With Soul Chat off, the first time you talk to your fren it tells you: "Bots run on commands: try /bot or open the Guide (]). To let them talk back, use AI setup in the Guide."

## What a fren can and can't do in chat

A fren in Soul Chat **only talks**. It can't run commands, start jobs, move, or change its inventory because of what it says. "Sure, I'll go chop wood" is just words. Give the order with a command or a button.

Replies are kept short, a few sentences at most.

## Personas

Each fren speaks as a persona:

- **Jake**, a practical field engineer
- **Bob**, a curious naturalist and tinkerer
- **Silas**, an old miner, short with words and superstitious

A fren named after a persona gets it. Any other fren speaks as Jake until you pick one with **Pick…** in the setup checklist.

## Memory

A fren remembers your conversations with it, and once a Minecraft day it sums up what you told it into facts it keeps. Memory is kept per world and per player: what you tell a fren privately is never used in lines other players might hear, unless you are the only player online.

| Command | What it does |
|---|---|
| `/bot soul memory <bot>` | Lists what that fren remembers about you |
| `/bot soul reset <bot>` | Starts a fresh conversation with that fren. The old one, and its memories of you, are archived, not deleted. |
| `/bot soul reset party` | The same for your group chats |
| `/bot soul digest on`, `off` or `status` | Turns the daily summing-up on or off (operator or host) |

These are typed `/bot` commands, so on a server they need an operator or the host.

## Chatter between frens

Both of these are off by default.

- **Banter** (`/bot soul banter on`, or Soul Banter in Bot Controls): frens near each other chat among themselves now and then. Idle frens about every 8 to 15 minutes. With **Soul Active** on, working frens too, about every 4 to 8 minutes. You have to be within 24 blocks. **Rate…** makes it more or less often.
- **Local chat** (`/bot soul local on`): frens within 16 blocks may react to things you say without naming them, every 6 to 12 minutes at most. While it is off, nothing you say unaddressed is recorded.

## Voice

Frens can speak their replies aloud. Turn on **Soul Voice** in Bot Controls, or use `/bot soul voice on`. **Eng…** picks and installs the voice engine. `/bot soul voice list` shows the voices, and `/bot soul voice install <voice>` downloads a Piper voice (operator or host). The voice engines are listed in the [README](https://github.com/wcfcarolina13/Frens/blob/main/README.md).

## When it doesn't answer

| What you see | What to do |
|---|---|
| Nothing at all | Check Soul Chat is on and that fren is enabled. With Soul Chat on but the fren not enabled, there is no message. Run `/bot soul status Lia`. |
| "Lia's local conversation model is unavailable." | Ollama isn't running or refused. Start it and press Refresh in the setup checklist. |
| "Lia's local conversation model is not ready:" and a reason | Usually no model is picked. Choose one in the setup checklist. |
| "Lia didn't answer in time." | The model took longer than 60 seconds. Try a smaller model. The first reply after loading is the slowest. |
| "Lia couldn't form a usable reply." | The model's answer couldn't be used. Ask again. |
| "Lia is tied up answering something else. Try again in a moment." | Only one reply is written at a time on the whole server, and the queue is full. |
| "Your companions are still talking. Give them a moment." | A group chat is still going. |
| "Lia's conversation memory is still loading." | Wait a moment and try again. |

Frens don't fall back to scripted lines when Ollama is down. They just tell you it is unavailable.

A reply usually takes a few seconds and can cause small frame drops while the model works. Ollama keeps the model loaded for an hour after the last reply, which uses memory.

## Privacy

Frens only talks to the Ollama address it is set to, by default on your own computer. It sends the model the fren's persona, your recent conversation, what the fren remembers, and some game details such as where it is, nearby mobs and what it is holding. A fren that is far away isn't told what is around you. At the normal log level, the game logs record how long replies took, never what was said. Downloading a model fetches it from Ollama's registry.
