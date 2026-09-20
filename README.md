# AIBuild

Describe something with `/aibuild` and an AI model builds it in your world, block by block.

Works in singleplayer and on a dedicated server. It is server side only, so **other players
on your server do not need to install anything**.

Minecraft **26.3**, Fabric, Java 25.

```
/aibuild a compact 2x2 piston door
/aibuild a cactus farm with a chest
/aibuild a small stone bridge with lanterns
```

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

## Which jar

Both are built from identical code. They differ only in the defaults they ship and where they
will load.

- **`aibuild-<version>.jar`** for singleplayer, and for servers if you want the relaxed
  defaults. Loads anywhere. No operator requirement, no cooldown, 20000 block limit.
- **`aibuild-<version>-server.jar`** for a dedicated server. Operator only, 30 second cooldown,
  10000 block limit, smaller build radius.

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

## Honest expectations

The mod faithfully builds whatever the model specifies. Whether that redstone **works** is down
to the model, and the current generation is not reliable at anything complicated. Simple logic
gates, farms and decorative builds come out well. Piston doors and anything needing precise
timing usually do not, though they often come close enough to fix by hand.

The validator, the local coordinate space and the repair pass raise the hit rate a long way.
They do not make it a redstone engineer.

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
