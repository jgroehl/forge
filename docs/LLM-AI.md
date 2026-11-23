# LLM AI Players

An **LLM AI** seat is played by a large language model instead of Forge's built-in AI. Forge asks the model for every strategic decision (what to cast, attacks, blocks, targets, mulligans, and so on) and hands the purely mechanical ones to the regular Forge AI.

The model can run on your own computer through a local server, or be a hosted model reached with an API key. Both use the same OpenAI-compatible *chat completions* API.

## 1. Choose where the model runs

### Option A: a local server (no API key)

Any server that offers an OpenAI-compatible API works, for example LM Studio, Ollama, llama.cpp or vLLM.

1. Load a model and start the server. LM Studio listens on `localhost:1234` by default.
2. Note the model name the server reports, for example `qwen3-vl-4b`.

Nothing leaves your computer, and games cost nothing beyond electricity. Small models play noticeably worse than large ones.

### Option B: a hosted API (with an API key)

Create an API key with the provider, then use its OpenAI-compatible base URL:

| Provider | Address (base URL) | Model |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | an id from OpenAI's model list |
| Anthropic (Claude) | `https://api.anthropic.com/v1` | for example `claude-opus-5-5` |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | an id from Google's model list |
| OpenRouter | `https://openrouter.ai/api/v1` | an id from OpenRouter's model list |

Any other service with an OpenAI-compatible chat completions endpoint and Bearer-token authentication works the same way. Check the provider's documentation for current model ids.

Notes:
- **Anthropic** offers this API as an OpenAI compatibility layer; use a regular Claude API key.
- **Cost and speed:** Forge sends one request per decision, typically 30 to 120 per LLM seat in a game, each carrying the full game state. Each game with a top-tier hosted model costs real money and plays slowly. Try a cheaper model first, and set a spending limit with your provider.

## 2. Configure Forge

**Desktop:** *Settings → AI Settings*. **Android/mobile:** *Settings*, then scroll to the *AI Settings* group.

| Setting | Local server | Hosted API |
|---|---|---|
| LLM address | `localhost:1234` | the base URL from the table above |
| LLM model name | the name your server reports | the provider's model id |
| API key | leave empty | your key (or use the environment variable below) |
| Prompt template | *Full* | *Full* |

The *Minimal* prompt template is only for models fine-tuned on Forge decisions.

### Keeping the API key out of the preferences file

A key typed into *AI Settings* is stored **in plain text** in `forge.preferences` (on Windows: `%APPDATA%\Forge\preferences\forge.preferences`). On desktop you can instead set the environment variable `FORGE_LLM_API_KEY` before starting Forge, and leave the field empty:

```bash
export FORGE_LLM_API_KEY=your-key
```

On Windows (PowerShell), for the current user:

```powershell
[Environment]::SetEnvironmentVariable("FORGE_LLM_API_KEY", "your-key", "User")
```

Restart Forge afterwards. The environment variable takes precedence over the setting. Forge never writes the key to its logs.

## 3. Seat an LLM AI player

In a constructed lobby, set a seat to:
- **Desktop:** the **LLM AI** option, next to *Human* and *AI*.
- **Mobile:** switch the seat to *AI*, then flip the *ForgeAI | LLM AI* switch to *LLM AI*.

The seat's name gains an `[LLM]` tag. That tag is what marks the seat as an LLM player, so you can also add it to a name by hand.

## How the address is used

Forge turns the address into the chat completions URL like this:

| Address | Request goes to |
|---|---|
| a host without a path, such as `localhost:1234` | `http://localhost:1234/v1/chat/completions` |
| a URL with a path, such as `https://api.openai.com/v1` | `https://api.openai.com/v1/chat/completions` |
| a URL ending in `/chat/completions` | that URL, unchanged |

An address without `http://` or `https://` gets `http://`, so hosted APIs need the full `https://` URL.

Some hosted models reject parameters that local servers accept. When the server answers with an error naming one of these, Forge adjusts and retries, then keeps the change for the rest of the game:
- **`temperature`** is dropped. Current Claude models, for example, reject values below 1.
- **`max_tokens`** is replaced by `max_completion_tokens`, which OpenAI's reasoning models require.

## Command line and simulations

The same settings can be given as Java system properties, which override *AI Settings*. Pass them before `-jar`:

| Property | Meaning |
|---|---|
| `forge.external.agent.url` | LLM address |
| `forge.external.agent.model` | model name |
| `forge.external.agent.apiKey` | API key. Prefer `FORGE_LLM_API_KEY`, because command lines are visible to other programs. |
| `forge.external.agent.systemPrompt` | `full` or `minimal` |
| `forge.external.agent.seats` | in `sim` mode, how many seats (from the first) the LLM plays; default 1 |
| `forge.external.agent.jsonSchema` | `true` asks the server to enforce the answer format with `response_format` (`json_schema`, strict) |
| `forge.external.agent.seatLogDir` | folder for the per-seat decision logs |

In `sim` mode (see [AI](AI.md)), setting `forge.external.agent.url` makes the first seat an LLM player named `[LLM]AI`.

## Troubleshooting

**The LLM player makes odd or random-looking choices.** When a request fails, Forge picks the first option and carries on, so a broken setup looks like a bad player. Check the log (`forge.log` in the Forge user folder, `%APPDATA%\Forge` on Windows):
- `LLM API returned status 401 (check the LLM API key)` or `403`: the key is missing or wrong, or the environment variable is not visible to Forge (restart Forge after setting it).
- `LLM API returned status 404`: the address or model name is wrong. Compare the address with the table above.
- `Failed to call LLM API at …`: Forge cannot reach the server. Check that it is running, and the host and port.
- `LLM API returned status 429`: you hit the provider's rate limit.

**Where are the model's decisions?** Each LLM seat writes its prompts and answers to its own file in `llm_games` in the Forge user folder. The startup line `LLM agent: model … at …` in `forge.log` shows the URL Forge uses and whether it has an API key.
