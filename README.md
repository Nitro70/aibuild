# AIBuild

Describe something with `/aibuild` and an AI model builds it in your world, block by block.

Works in singleplayer, on a server that has the mod, **and on a server that does not**:

- **Server has AIBuild:** only the server needs it. Other players install nothing.
- **Server does not have AIBuild:** install the mod on your own client and be an operator.
  Builds go out as ordinary `/setblock` and `/fill` commands.

Minecraft **26.3**, Fabric, Java 25.

```
/aibuild a compact 2x2 piston door
/aibuild a cactus farm with a chest
/aibuild a small stone bridge with lanterns
```

## Build modes

Set with `/aibuild mode <auto|direct|commands>`, or the **Build mode** toggle in
`/aibuild config`.

| Mode | How it builds | Needs |
|---|---|---|
| **auto** (default) | Direct when the server has the mod, commands when it does not | whichever applies |
| **direct** | The server side mod writes the blocks | AIBuild on the server |
| **commands** | Your client sends `/setblock` and `/fill` | the client mod, and OP |

Singleplayer always counts as having the mod, since your own game runs the same jar.

**Direct is the better result when you can get it.** It holds back every redstone update until
the whole build is down, so nothing fires half built. Commands mode cannot do that: each
`/setblock` updates its neighbours as it lands, so redstone may react while it builds. The
support first ordering still applies, so torches and dust land on something.

In both modes **the build goes where you were standing when you ran the command**, facing the
way you were facing, in the dimension you were in. Walk off while the model thinks and it still
lands in the right place. In commands mode every command is pinned with `execute in <dimension>`
so even changing dimension mid build cannot move it.

### Commands mode, in detail

- **Checks before it spends anything.** A server only sends you the commands you are allowed
  to run, so before the model is asked, the mod reads that list and confirms you can use
  `/setblock`, `/fill` and `/execute`. If not, it says which one is missing and stops.
- **Straight runs of the same block become one `/fill`**, so a floor costs a handful of
  commands rather than hundreds. Nothing is ever reordered to make that happen.
- **Never uses `strict` mode.** Strict skips the neighbour shape pass, which leaves panes,
  fences and stairs unconnected.
- **Your chat stays readable.** The "Changed the block at..." line each command prints is
  hidden while a build runs, and counted instead, so the summary tells you how many failed
  and shows the first failure. Turn **Hide command spam** off to see them all.
- **Undo works.** Before sending anything, the mod reads what is currently at every position
  from your copy of the world, and `/aibuild undo` puts it back. It restores blocks, not
  container contents, so undoing over a chest brings the chest back empty. Undo history is
  cleared when you change server, so it can never touch the wrong world.
- **Commands per tick** (default 16) sets the pace. Operators are exempt from the chat spam
  kick, which is why this can run fast. If a server grants `/setblock` through a permissions
  plugin without making you an operator, you are *not* exempt: set commands per tick to 1.

## Pick your own AI

There are no API keys in this repository and there never will be. You choose a provider and
supply your own key.

| Provider | id | Key needed |
|---|---|---|
| Google Gemini | `gemini` | yes |
| OpenAI | `openai` | yes |
| Anthropic | `anthropic` | yes |
| **Claude Code CLI** | `claude-cli` | **no, uses your signed in subscription** |
| DeepSeek | `deepseek` | yes |
| Groq | `groq` | yes |
| xAI Grok | `xai` | yes |
| Mistral | `mistral` | yes |
| OpenRouter | `openrouter` | yes |
| Together AI | `together` | yes |
| Ollama (local) | `ollama` | no |
| LM Studio (local) | `lmstudio` | no |
| Anything OpenAI compatible | `custom` | set your own base URL |

Each provider's endpoint is already built in, so setup is just the key.

## Settings screen

```
/aibuildconfig
```

Everything is picked from a list. Nothing has to be typed exactly right, because getting a
provider or model id slightly wrong is the difference between working and a baffling 404.

- **Provider** opens a page of buttons, each marked `[key set]`, `[needs key]` or `[no key]`.
- **Model** has a **Choose** button that asks the provider what your key can actually reach,
  then shows those as buttons too.
- **API key** is a paste field. If a key is already saved it shows dots rather than the key,
  and is only overwritten if you type something new. If the key comes from an environment
  variable the field says so and locks.
- **Build settings** has sliders and on/off buttons for size, speed, temperature and the rest,
  so nothing can be set to a nonsensical value in the first place.

The screen edits the config file on your own machine. In singleplayer that is the one in use.
If you are connected to someone else's server it is not, and the screen says so at the top
rather than pretending the change took effect.

The chat commands all still work if you prefer them:

```
/aibuild provider              list them, with a marker on the one in use
/aibuild provider gemini       switch
/aibuild models                ask that provider what models your key can reach
/aibuild model gemini-2.5-pro  pick one
/aibuild status                what is configured right now
```

### Claude without an API key

`claude-cli` is the odd one out. Instead of calling an API it runs the Claude Code CLI on the
machine hosting the world, so if you are already signed in to Claude Code it uses that
subscription and needs no key at all.

```
/aibuild provider claude-cli
```

It needs `claude` on the PATH of whatever runs the game. If it is somewhere else, set
`claudeCliPath` in the config to the full path.

## Setting your key

Two ways, and the environment variable wins if both are set.

**Config file.** `config/aibuild.json`, created the first time the mod runs. Paste the key
into the entry for your provider:

```json
"providers": {
  "gemini": { "apiKey": "paste-it-here", "model": "gemini-2.5-pro" }
}
```

Then `/aibuild reload`.

**Environment variable.** `GEMINI_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`,
`DEEPSEEK_API_KEY`, `GROQ_API_KEY`, `XAI_API_KEY`, `MISTRAL_API_KEY`, `OPENROUTER_API_KEY`,
`TOGETHER_API_KEY`. Better on a server, since the key never touches the world directory.

`config/aibuild.json` is in `.gitignore`. There is deliberately **no chat command for setting a
key**, because chat is written to the server log, and a key in a log is a key that has leaked.

## Commands

| Command | What it does |
|---|---|
| `/aibuild <what to build>` | Build it |
| `/aibuild undo` | Put back what the last build replaced |
| `/aibuild cancel` | Stop a build that is still going |
| `/aibuild status` | Provider, model, whether a key is set, current limits |
| `/aibuild provider [id]` | List or switch provider |
| `/aibuild model <id>` | Set the model for the current provider |
| `/aibuild models` | Ask the provider what it offers |
| `/aibuild reload` | Reread the config file |
| `/aibuild mode <auto\|direct\|commands>` | Choose how builds reach the world |
| `/aibuild config` | Open the settings screen (also `/aibuildconfig`) |
| `/aibuild serverinfo` | Whether this server runs AIBuild, and which provider it uses |

## Which jar

Both are built from identical code. They differ only in the defaults they ship and where they
will load. **For playing on someone else's server, you want the normal jar on your client.**

- **`aibuild-<version>.jar`** for singleplayer, and for servers if you want the relaxed
  defaults. Loads anywhere. No operator requirement, no cooldown, 20000 block limit.
- **`aibuild-<version>-server.jar`** for a dedicated server. Operator only, 30 second cooldown,
  10000 block limit, smaller build radius. Contains no client code at all, so there is no
  settings screen: edit the config file on the server and run `/aibuild reload`.

Either way, only the host installs it.

## How it works

1. Your prompt goes to the model along with the block budget and the coordinate rules.
2. The model returns a structured list of `place` and `fill` operations in **local
   coordinates**, so it never sees where in the world you are standing.
3. Every block id and block state is checked against the **live block registry** before
   anything is written. If something is wrong, the model gets the specific complaints back and
   one chance to fix it.
4. The build is sorted into passes: blocks that stand on their own go down first, bottom up,
   then everything that clings to them. A redstone torch placed before its support block just
   drops as an item.
5. Blocks are written with **neighbour updates suppressed**, then updated in a single pass at
   the end. Without this a contraption starts running while it is half built and pistons shove
   the rest of the plan out of alignment.
6. The build plays out over several ticks so the server does not stall, and so you can watch.

Everything except step 6 lives in the `core` module, which has no Minecraft imports at all and
is covered by 68 unit tests.

## Control port

An optional local TCP port for driving the mod from outside the game: scripting builds, or
handing an AI agent the keys so it can build and debug for you.

**Off by default.** Anything that reaches this port can place blocks and spend your API
credit, so it binds to loopback only, and refuses to bind anywhere else unless you set
`controlToken`.

```json
"controlPortEnabled": true,
"controlPort": 25585,
"controlBindAddress": "127.0.0.1",
"controlToken": ""
```

One JSON object per line in, one per line out, so netcat works. There is a small client for
convenience:

```bash
python tools/aibuild.py status
python tools/aibuild.py prompt "a cactus farm with a chest"
python tools/aibuild.py inspect 120 64 -30
```

| Command | What it does |
|---|---|
| `ping` | Is it alive, and which Minecraft |
| `status` | Provider, model, whether a key is set, limits, who is online |
| `ask {text}` | Send a prompt to the model and get its **raw reply**. Builds nothing |
| `plan {text}` | Model plus validation, returns the finished plan. Builds nothing |
| `prompt {text}` | The full thing, blocks appear in the world |
| `place {plan}` | Build a plan you wrote yourself, **no model involved** |
| `undo` / `cancel` | As the chat commands |
| `inspect {x,y,z}` | Read the block state actually in the world |
| `blocks {query}` | Search the block registry for real ids |
| `players` | Who is online |

The three that matter for debugging are `ask`, `place` and `inspect`. Between them they tell
you whether the model said something odd, whether the mod mishandled something sensible, and
what actually ended up in the world. A hand written `place` plan goes through exactly the same
validator as a generated one, so it cannot sneak past the block checks.

## Honest expectations

The mod faithfully builds whatever the model specifies. Whether that redstone **works** is down
to the model, and the current generation is not reliable at anything complicated. Simple logic
gates, farms and decorative builds come out well. Piston doors and anything needing precise
timing usually do not, though they often come close enough to fix by hand.

The validator, the local coordinate space and the repair pass raise the hit rate a long way.
They do not make it a redstone engineer.

## If the provider says it is busy

A **503** means the model is overloaded on the provider's side, not that anything is wrong with
your key or the mod. Gemini words it as "this model is currently experiencing high demand".

AIBuild retries twice, starting after about a second and waiting a little longer each time, and
tells you in chat while it does. If the provider says how long to wait, that wait is used instead.
Errors that retrying cannot fix, like a bad key, are never retried.

It retries only twice because **a refused request is not free**. On Gemini's free tier each model
allows 20 requests a day, and failed requests count toward it too. Retrying harder can spend most
of a day's allowance on one build. When the allowance is gone, AIBuild says so straight away and
moves to your fallback model, which has an allowance of its own, instead of retrying something
that cannot work until tomorrow. It resets at midnight Pacific time.

**Big builds are refused far more often than small ones.** Measured on one key, one model, in
the same half hour:

| Asked for | Answered |
|---|---|
| "build a normal sized house" | 2 out of 3 |
| a wooden mansion "just under 50000 blocks" | 1 out of 11 |

The big ones fail partway through, after anything from a few seconds to a minute and a half, so
they are not being turned away at the door. The 11 include the mod's normal request plus three
variations on it: streaming the reply, turning down the model's thinking, and dropping the
response schema. None of them changed the picture. If a big build keeps coming back busy, build it in parts
("the ground floor of a desert mansion", then "the upper floor"), or use a provider without the
free tier limits, such as `claude-cli` with your Claude subscription.

**If it stays busy, change model before assuming anything is broken, and do not reach for the
newest one.** Everybody queues for the newest model, so it is often the least available. Measured
against one key in one sitting, with the same request sent to each:

| Model | Answered |
|---|---|
| newest flash | 0 out of 3 |
| the one before it | 0 out of 3 |
| **one generation back** | **3 out of 3** |

Run `/aibuild models` to see what your key can reach, then `/aibuild model <id>`, or pick it from
the list in `/aibuild config`. A model that is a generation old is usually both quicker to answer
and far more likely to answer at all.

If one model stays busy for a while, give it a fallback. Overload is usually one model rather than
the whole provider, so a smaller or older one often answers while the main one is swamped. In
`config/aibuild.json`, under your provider:

```json
"gemini": { "apiKey": "...", "model": "gemini-3.5-flash", "fallbackModel": "gemini-2.5-flash" }
```

Run `/aibuild models` to see which ids your key can use.

You also cannot start a second build while the first is still waiting on the model, so typing the
command again during an outage no longer piles extra requests onto a provider that is already
struggling.

## If redstone comes out backwards

Set `flipRepeaterFacing` to `true` in the config and `/aibuild reload`.

Repeater and comparator facing is a well known source of confusion, and models are not
consistent about which convention they use. Rather than guess in the prompt, this is left as a
switch: if your contraptions come out with the repeaters consistently pointing the wrong way,
flip it once and leave it.

## Settings

All in `config/aibuild.json`.

| Setting | Default | What it does |
|---|---|---|
| `provider` | `gemini` | Which provider to use |
| `temperature` | `0.4` | Lower is more literal |
| `maxOutputTokens` | `32768` | Raise for very large builds |
| `requestTimeoutSeconds` | `180` | How long to wait for the model |
| `maxRetries` | `2` | Retries when the provider is busy or rate limited. Each one counts toward a free tier's daily limit. `0` turns it off |
| `providers.<id>.fallbackModel` | empty | A second model to try if the first stays busy |
| `maxBlocks` | `20000` | Hard cap per build |
| `maxRadius` / `maxHeight` | `48` | How far a build may reach from its origin |
| `blocksPerTick` | `12` | Build speed. `0` places everything at once |
| `originOffset` | `3` | How far in front of you the build starts |
| `rotateToPlayer` | `true` | Turn the build to face you |
| `flipRepeaterFacing` | `false` | See above |
| `repairOnInvalidPlan` | `true` | Give the model one chance to fix itself |
| `requireOperator` | varies | Operator only |
| `cooldownSeconds` | varies | Wait between builds |
| `undoHistory` | `10` | How many builds can be undone |
| `blacklist` | bedrock, command blocks, barriers... | Never placed, whatever the model asks |

## Building it

Needs JDK 25. The Gradle toolchain path is set in `gradle.properties`.

```bash
./gradlew build
```

Jars land in `fabric/build/libs/`.

Worth knowing if you are poking at the build: Minecraft 26.1 and later ship **unobfuscated**,
so there is no mappings declaration, no `modImplementation` and no `remapJar`. Yarn publishes
nothing for 26.x for that reason. Class names in the source are the real ones.

## Licence

MIT.
