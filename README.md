# JP AI Bridge

Makes AI companion mods answer in Japanese by rewriting their language instructions through a local bridge.

AI系のMod（AIキャラクターと会話するModなど）の返事を日本語にする中継Modです。（日本語の説明は下にあります）

**Download:** [Releases](../../releases/latest) → `jpaibridge-<version>.jar`

- Minecraft 1.20.1 / Forge 47+
- Unofficial. Not affiliated with the authors of any AI companion mod.

## English

JP AI Bridge starts a small local server (127.0.0.1 only) inside Minecraft. AI companion mods that let you set a custom / local OpenAI-compatible address can send their requests to it. The bridge replaces "answer in English / Spanish / Portuguese" style instructions with a Japanese instruction, then forwards the request to the AI service you choose (Groq, OpenAI, or any OpenAI-compatible API) using your own API key. Speech-to-text requests are forwarded with `language=ja`.

- Does not include or modify any other mod's code or assets
- Your conversations are sent to the AI service you configure (that is the purpose of the mod). Nothing else is collected or sent
- Your API key is stored in plain text in `config/jpaibridge.properties`. Do not share that file
- Works on client and dedicated server: install it on the side that talks to the AI API

### Setup

1. Put the jar in `mods` and start the game once. `config/jpaibridge.properties` is created
2. Close the game and set `chat.upstream`, `chat.key` and `chat.model`
3. In your AI companion mod, set the local / custom server address to `http://127.0.0.1:5010/v1/`

| Upstream | chat.upstream | chat.model | stt.model |
|---|---|---|---|
| OpenAI (paid) | https://api.openai.com/v1/ | gpt-5.6-luna | whisper-1 |
| Groq (free tier, rate limited) | https://api.groq.com/openai/v1/ | openai/gpt-oss-120b | whisper-large-v3 |
| Gemini (free tier) | https://generativelanguage.googleapis.com/v1beta/openai/ | gemini-3.5-flash-lite | (leave as is) |

With Gemini, one API key covers both chat and microphone. Replies are slower and transcription is less accurate than OpenAI. Google's terms require API users to be 18 or older. On the free tier, Google may use your input (including microphone audio) to improve its products and human reviewers may read it, so do not say anything personal.

Pressing the microphone key without speaking can produce made-up words, and the AI companion may answer them. This happened with both OpenAI and Gemini in testing.

Set `debug=true` to write the rewritten prompt and each reply to `logs/latest.log` (for troubleshooting).

## 日本語

ゲームの中に、PC内だけで使う小さな中継サーバー（127.0.0.1）を立てます。AI系Modが送る「英語で答えて」などの言語の指定を「日本語で答えて」に書き換えてから、自分で設定したAI（OpenAI・Groqなど）へ送ります。マイクの文字起こしには日本語の指定を付けます。

- ほかのModのプログラムや素材は含まず、書き換えもしません
- 会話は、自分で設定したAIのサービスに送られます（このModの役割です）。それ以外の情報は集めも送りもしません
- APIキーは `config/jpaibridge.properties` にそのまま保存されます。このファイルは人に渡さないでください
- 非公式のModです。各AI系Modの作者とは関係ありません

### 使い方

1. jarを `mods` フォルダに入れて一度起動する。`config/jpaibridge.properties` ができる
2. ゲームを閉じて、`chat.upstream`・`chat.key`・`chat.model` を書き換える（上の表）
3. AI系Modの「ローカル／カスタムサーバー」の送り先を `http://127.0.0.1:5010/v1/` にする

Geminiの無料枠でも使えます。キーはGeminiの1つだけで、会話もマイクも動きます。ただしOpenAIより返事が遅く、聞き取りの間違いも増えます。Googleの規約で、APIの利用は18歳以上に限られています。無料枠では、入力（マイクの音声を含む）がGoogleの製品改善に使われ、人が読むこともあるので、個人情報は話さないでください。

話さずにマイクのキーだけ押すと、言葉が作られてAIが返事をすることがあります（OpenAI・Geminiとも確認）。

### FemVerity（1.4）での設定

| 部品 | 設定 |
|---|---|
| 会話 | 会話用のキー（OpenAI・Gemini・Grok）を全部空にし、Advanced の Chat endpoint を `http://127.0.0.1:5010/v1/` |
| マイク | 「OpenAI key」欄を空にし、Microphone endpoint を `http://127.0.0.1:5010/v1/` |
| 声（Fish Audio） | Fish Audioのキーと声のIDを入れ、Voice endpoint は `http://127.0.0.1:8880/v1/` のまま |

FemVerity: Mod created by Espark – Available on CurseForge (https://www.curseforge.com/minecraft/mc-mods/femv)

## Build

Java 17 JDK required. `./build.sh` builds `build/jpaibridge-<version>.jar`; `./build.sh test` also runs a self test (no Minecraft needed).

## AI disclosure

Most of this mod's code and this README were written with the help of an AI assistant (Claude), under the author's direction and testing.

## License

MIT (see LICENSE.txt)
