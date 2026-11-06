package com.aperogames.jpaibridge.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Plain-Java self test (no JUnit): fake upstream + bridge + FemVerity-like requests. */
public final class BridgeSelfTest {

    static int failures = 0;

    static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + label);
        if (!ok) failures++;
    }

    static HttpResponse<String> postChat(HttpClient client, int port, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<String> lastBody = new AtomicReference<>();
        AtomicReference<String> lastAuth = new AtomicReference<>();
        AtomicReference<String> lastPath = new AtomicReference<>();
        AtomicReference<String> lastContentType = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            lastBody.set(new String(body, StandardCharsets.ISO_8859_1));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(ex.getRequestURI().getPath());
            lastContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            int status = 200;
            byte[] reply = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"こんにちは\"}}],\"usage\":{\"prompt_tokens\":3120,\"completion_tokens\":42,\"total_tokens\":3162}}"
                    .getBytes(StandardCharsets.UTF_8);
            // Mimic gpt-5.6 on /v1/chat/completions (messages copied from the real OpenAI errors of 2026-10-29).
            if (ex.getRequestURI().getPath().startsWith("/strict/")) {
                JsonObject sent = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                String effort = sent.has("reasoning_effort") ? sent.get("reasoning_effort").getAsString() : null;
                boolean tools = sent.has("tools") && sent.getAsJsonArray("tools").size() > 0;
                String err = null;
                if (sent.has("max_tokens")) {
                    err = "Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.";
                } else if ("minimal".equals(effort)) {
                    err = "Unsupported value: 'reasoning_effort' does not support 'minimal' with this model. Supported values are: 'none', 'low', 'medium', 'high', and 'xhigh'.";
                } else if (tools && !"none".equals(effort)) {
                    err = "Function tools with reasoning_effort are not supported for gpt-5.6-luna in /v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort to 'none'.";
                }
                if (err != null) {
                    status = 400;
                    JsonObject e = new JsonObject();
                    JsonObject inner = new JsonObject();
                    inner.addProperty("message", err);
                    inner.addProperty("param", err.contains("max_tokens") ? "max_tokens" : "reasoning_effort");
                    e.add("error", inner);
                    reply = e.toString().getBytes(StandardCharsets.UTF_8);
                }
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, reply.length);
            ex.getResponseBody().write(reply);
            ex.close();
        });
        upstream.start();
        int upPort = upstream.getAddress().getPort();

        Path tmp = Files.createTempDirectory("jpbridge");
        Path cfgFile = tmp.resolve("jpaibridge.properties");
        BridgeConfig first = BridgeConfig.load(cfgFile); // writes defaults
        check(Files.exists(cfgFile), "default config file written");
        BridgeConfig cfg = BridgeConfig.load(cfgFile);   // reads them back
        check(cfg.rules.size() == first.rules.size() && cfg.rules.size() >= 4, "rules survive save/load (" + cfg.rules.size() + ")");
        check(cfg.instruction.equals(BridgeConfig.DEFAULT_INSTRUCTION), "instruction survives save/load");

        // A 0.1.0-0.1.3 style file (old instruction + the 4 old rules saved in it) must pick up the new built-ins.
        Path oldFile = tmp.resolve("old.properties");
        Files.writeString(oldFile, "chat.key=abc\n"
                + "instruction=[LANGUAGE\\: always answer in natural, casual Japanese (日本語), whatever language you were told to use before and whatever language the player writes in. Never answer in English unless the player explicitly asks for English in this very message.]\n"
                + "rule.1.regex=\\\\[LANGUAGE\\:[^\\\\]]*\\\\]\nrule.1.replace={INSTRUCTION}\n", java.nio.charset.StandardCharsets.UTF_8);
        BridgeConfig old = BridgeConfig.load(oldFile);
        check(old.instruction.equals(BridgeConfig.DEFAULT_INSTRUCTION), "old default instruction upgraded");
        check(old.rules.size() == BridgeConfig.builtinRuleList().size() && old.extraRules.isEmpty(),
                "old saved built-in rule not duplicated, new built-ins present (" + old.rules.size() + ")");

        // FemVerity work-dialogue prompt (gift reaction) with an inline "in natural English".
        Rewriter rw = new Rewriter(cfg);
        String work = "[VERITY_WORK_DIALOGUE: responde a una orden de trabajo] Escribe exactamente una oración completa in natural English, de 8 a 22 palabras.";
        String workOut = rw.rewriteText(work);
        check(!workOut.contains("English"), "inline 'in natural English' rewritten: " + workOut);
        check(rw.rewriteText("contesta en español latino mexicano natural").contains("japonés"), "inline Spanish rewritten");
        check(rw.rewriteText("responda em português brasileiro natural").contains("japonês"), "inline Portuguese rewritten");
        check(!rw.rewriteText(BridgeConfig.DEFAULT_INSTRUCTION).equals("") && rw.rewriteText("[LANGUAGE: x]").equals(BridgeConfig.DEFAULT_INSTRUCTION),
                "instruction text is stable under the rules");
        // OpenAI reasoning models: FemVerity's server-path body uses max_tokens + temperature (rejected with HTTP 400).
        JsonObject gpt5 = JsonParser.parseString(rw.rewriteChat(
                "{\"model\":\"gpt-5.6-luna\",\"max_tokens\":300,\"temperature\":0.8,\"top_p\":0.9,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .getAsJsonObject();
        check(!gpt5.has("max_tokens") && gpt5.get("max_completion_tokens").getAsInt() == 300, "gpt-5: max_tokens -> max_completion_tokens");
        check(!gpt5.has("temperature") && !gpt5.has("top_p"), "gpt-5: sampling fields removed");
        check("low".equals(gpt5.get("reasoning_effort").getAsString()), "gpt-5: reasoning_effort=low added");
        JsonObject oss = JsonParser.parseString(rw.rewriteChat(
                "{\"model\":\"openai/gpt-oss-120b\",\"max_tokens\":300,\"temperature\":0.8,\"messages\":[]}")).getAsJsonObject();
        check(oss.has("max_tokens") && oss.has("temperature") && !oss.has("reasoning_effort"), "gpt-oss (Groq) left untouched");
        cfg.port = 0;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            cfg.port = probe.getLocalPort();
        }
        cfg.chatUpstream = "http://127.0.0.1:" + upPort + "/openai/v1/";
        cfg.chatKey = "gsk_TESTKEY";

        BridgeServer bridge = new BridgeServer(cfg, s -> System.out.println("  [bridge] " + s));
        bridge.start();
        HttpClient client = HttpClient.newHttpClient(); // default HTTP_2 like FemVerity -> h2c upgrade attempt

        String femBody = "{\"model\":\"openai/gpt-oss-120b\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"You are FemVerity. IMPORTANTE: hablas y respondes SIEMPRE en espa\\u00f1ol; el campo \\\"message\\\" debe estar SIEMPRE en espa\\u00f1ol, sin importar el idioma del jugador. Speak naturally in the player's language (Spanish, English or Brazilian Portuguese).\"},"
                + "{\"role\":\"user\",\"content\":\"[LANGUAGE: always answer in natural English, whatever language you are written to in. Keep it conversational, not formal. Do not mix in Spanish words.] Player asked: \\u3053\\u3093\\u306b\\u3061\\u306f\"}],"
                + "\"tools\":[],\"temperature\":0.8}";
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cfg.port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(femBody)).build(), HttpResponse.BodyHandlers.ofString());
        check(r.statusCode() == 200, "chat status 200");
        check(r.body().contains("こんにちは"), "upstream reply passed back unchanged");
        check("/openai/v1/chat/completions".equals(lastPath.get()), "forwarded path: " + lastPath.get());
        check("Bearer gsk_TESTKEY".equals(lastAuth.get()), "upstream key attached");
        String sent = new String(lastBody.get().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        JsonObject sentJson = JsonParser.parseString(sent).getAsJsonObject();
        String all = sentJson.getAsJsonArray("messages").toString();
        check(!all.contains("natural English"), "English language tag removed");
        check(!all.contains("SIEMPRE en espa"), "Spanish lock sentence removed");
        check(!all.contains("Brazilian Portuguese"), "language list replaced");
        check(all.contains("Japanese"), "Japanese instruction present");
        check(sentJson.has("tools") && sentJson.get("temperature").getAsDouble() == 0.8, "other fields untouched");
        check(all.contains("\\u3053\\u3093") || all.contains("こんにちは"), "player text kept");
        System.out.println("  sent messages: " + sent.substring(0, Math.min(700, sent.length())));

        // Multipart transcription without a language field.
        String boundary = "----FemBoundary123";
        byte[] wav = new byte[]{82, 73, 70, 70, 0, 1, 2, 3, (byte) 0xFF, 13, 10, 45, 45};
        java.io.ByteArrayOutputStream mp = new java.io.ByteArrayOutputStream();
        mp.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n").getBytes(StandardCharsets.ISO_8859_1));
        mp.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        mp.writeBytes(wav);
        mp.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cfg.port + "/v1/audio/transcriptions"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(mp.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        check(r.statusCode() == 200, "transcription status 200");
        String stt = lastBody.get();
        check(stt.contains("name=\"language\"\r\n\r\nja\r\n"), "language=ja added");
        check(stt.contains("name=\"model\"\r\n\r\nwhisper-large-v3\r\n"), "model swapped to whisper-large-v3");
        byte[] raw = stt.getBytes(StandardCharsets.ISO_8859_1);
        check(Rewriter.indexOf(raw, wav, 0) >= 0, "audio bytes preserved");
        check("/openai/v1/audio/transcriptions".equals(lastPath.get()), "stt forwarded path");

        // GET models passthrough.
        r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cfg.port + "/v1/models")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        check(r.statusCode() == 200 && "/openai/v1/models".equals(lastPath.get()), "models passthrough");

        // Gemini has no audio/transcriptions: the audio goes through chat/completions, the mod still gets {"text": ...}.
        check(!cfg.sttViaChat(), "stt.mode=auto leaves other upstreams on audio/transcriptions");
        cfg.sttUpstream = "https://generativelanguage.googleapis.com/v1beta/openai/";
        check(cfg.sttViaChat(), "stt.mode=auto detects Gemini");
        cfg.sttUpstream = "";
        cfg.sttMode = "chat";
        cfg.chatModel = "gemini-3.5-flash-lite";
        cfg.sttModel = "whisper-1";
        r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cfg.port + "/v1/audio/transcriptions"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(mp.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        check(r.statusCode() == 200 && "こんにちは".equals(JsonParser.parseString(r.body()).getAsJsonObject().get("text").getAsString()),
                "audio via chat: mod gets {\"text\": ...} (" + r.body() + ")");
        check("/openai/v1/chat/completions".equals(lastPath.get()), "audio via chat: forwarded path " + lastPath.get());
        check("application/json".equals(lastContentType.get()), "audio via chat: content type " + lastContentType.get());
        JsonObject audioChat = JsonParser.parseString(lastBody.get()).getAsJsonObject();
        check("gemini-3.5-flash-lite".equals(audioChat.get("model").getAsString()), "audio via chat: whisper name falls back to chat.model");
        JsonObject inputAudio = audioChat.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content")
                .get(1).getAsJsonObject().getAsJsonObject("input_audio");
        check("wav".equals(inputAudio.get("format").getAsString()), "audio via chat: format wav");
        check(java.util.Arrays.equals(wav, java.util.Base64.getDecoder().decode(inputAudio.get("data").getAsString())),
                "audio via chat: audio bytes preserved");
        check("{\"text\":\"\"}".equals(new String(Rewriter.transcriptFromChat(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)),
                "audio via chat: reply with no content becomes empty text");
        check("{\"text\":\"お腹空いた。Hello world タイマツ\"}".equals(new String(Rewriter.transcriptFromChat(
                "{\"choices\":[{\"message\":{\"content\":\"お腹 空い た 。Hello world タイ マツ\"}}]}".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)),
                "audio via chat: spaces inside Japanese removed, Latin words kept");
        check(Rewriter.transcriptFromChat("[{\"error\":{}}]".getBytes(StandardCharsets.UTF_8)) == null, "audio via chat: non-reply rejected");
        cfg.sttModel = "gemini-3.6-flash";
        check("gemini-3.6-flash".equals(cfg.sttChatModel()), "audio via chat: gemini stt.model is kept");

        bridge.stop();

        // Upstream that rejects reasoning_effort: the bridge must retry once without it.
        BridgeConfig strict = BridgeConfig.defaults();
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            strict.port = probe.getLocalPort();
        }
        strict.chatUpstream = "http://127.0.0.1:" + upPort + "/strict/v1/";
        strict.chatKey = "sk-TEST";
        strict.chatModel = "gpt-5.6-luna";
        BridgeServer strictBridge = new BridgeServer(strict, s -> System.out.println("  [strict] " + s));
        strictBridge.start();
        String tool = "{\"type\":\"function\",\"function\":{\"name\":\"drop_item\",\"parameters\":{\"type\":\"object\"}}}";
        // A) FemVerity-like request: tools + max_tokens + temperature -> must pass on the first try with effort=none.
        r = postChat(client, strict.port, "{\"model\":\"x\",\"max_tokens\":200,\"temperature\":0.8,\"tools\":[" + tool + "],\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonObject sentA = JsonParser.parseString(new String(lastBody.get().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)).getAsJsonObject();
        check(r.statusCode() == 200, "gpt-5.6 + tools: accepted (status " + r.statusCode() + ")");
        check("none".equals(sentA.get("reasoning_effort").getAsString()) && sentA.has("max_completion_tokens") && !sentA.has("temperature"),
                "gpt-5.6 + tools: sent reasoning_effort=none, max_completion_tokens, no temperature");
        // B) An old config that still says minimal, no tools -> error lists values -> retry with none.
        strict.reasoningEffort = "minimal";
        r = postChat(client, strict.port, "{\"model\":\"x\",\"max_tokens\":200,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        check(r.statusCode() == 200 && lastBody.get().contains("\"reasoning_effort\":\"none\""), "minimal rejected -> retried with none (status " + r.statusCode() + ")");
        // C) The mod itself sends a rejected effort with tools -> retry with the suggested 'none'.
        strict.reasoningEffort = "low";
        r = postChat(client, strict.port, "{\"model\":\"x\",\"reasoning_effort\":\"low\",\"tools\":[" + tool + "],\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        check(r.statusCode() == 200 && lastBody.get().contains("\"reasoning_effort\":\"none\""), "tools + low from mod -> retried with none (status " + r.statusCode() + ")");
        check("none".equals(Rewriter.retryEffort("set reasoning_effort to 'none'.", "low")), "retryEffort picks the suggested value");
        strictBridge.stop();
        upstream.stop(0);
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
