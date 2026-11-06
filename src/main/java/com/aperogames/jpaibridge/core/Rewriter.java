package com.aperogames.jpaibridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Rewrites OpenAI-compatible request bodies so the model answers in the target language.
 */
public final class Rewriter {

    private final BridgeConfig config;

    public Rewriter(BridgeConfig config) {
        this.config = config;
    }

    public String rewriteText(String text) {
        String out = text;
        for (BridgeConfig.Rule rule : config.rules) {
            String replacement = rule.replacement().replace("{INSTRUCTION}", config.instruction);
            out = rule.pattern().matcher(out).replaceAll(Matcher.quoteReplacement(replacement));
        }
        return out;
    }

    /** Rewrites a chat/completions JSON body. Returns the original string when it is not JSON. */
    public String rewriteChat(String body) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) return body;
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return body;
        }
        if (config.chatModel != null && !config.chatModel.isBlank()) {
            root.addProperty("model", config.chatModel);
        }
        adaptForReasoningModel(root);
        JsonArray messages = root.has("messages") && root.get("messages").isJsonArray()
                ? root.getAsJsonArray("messages") : null;
        if (messages == null) return root.toString();

        JsonObject lastUser = null;
        JsonObject firstSystem = null;
        for (JsonElement el : messages) {
            if (!el.isJsonObject()) continue;
            JsonObject msg = el.getAsJsonObject();
            rewriteContent(msg);
            String role = msg.has("role") ? msg.get("role").getAsString() : "";
            if (firstSystem == null && ("system".equals(role) || "developer".equals(role))) firstSystem = msg;
            if ("user".equals(role)) lastUser = msg;
        }

        if (config.addSystemMessage) {
            String note = config.instruction;
            if (firstSystem != null && firstSystem.has("content") && firstSystem.get("content").isJsonPrimitive()) {
                String content = firstSystem.get("content").getAsString();
                if (!content.contains(note)) firstSystem.addProperty("content", content + "\n\n" + note);
            } else {
                JsonObject sys = new JsonObject();
                sys.addProperty("role", "system");
                sys.addProperty("content", note);
                JsonArray rebuilt = new JsonArray();
                rebuilt.add(sys);
                messages.forEach(rebuilt::add);
                root.add("messages", rebuilt);
            }
        }

        if (lastUser != null && config.lastUserSuffix != null && !config.lastUserSuffix.isBlank()
                && lastUser.has("content") && lastUser.get("content").isJsonPrimitive()) {
            lastUser.addProperty("content", lastUser.get("content").getAsString() + config.lastUserSuffix);
        }
        return root.toString();
    }

    private static final java.util.regex.Pattern REASONING_MODEL =
            java.util.regex.Pattern.compile("(?i)^(?:openai/)?(?:gpt-5|o1|o3|o4)");

    /**
     * OpenAI reasoning models (gpt-5.x, o-series) reject max_tokens, temperature and similar sampling fields.
     * Mods that think they are talking to a local server send the classic fields, so adapt them here.
     */
    void adaptForReasoningModel(JsonObject root) {
        String model = root.has("model") && root.get("model").isJsonPrimitive() ? root.get("model").getAsString() : "";
        if (!REASONING_MODEL.matcher(model).find() || model.toLowerCase(java.util.Locale.ROOT).contains("gpt-oss")) return;
        if (root.has("max_tokens")) {
            if (!root.has("max_completion_tokens")) root.add("max_completion_tokens", root.get("max_tokens"));
            root.remove("max_tokens");
        }
        for (String field : new String[]{"temperature", "top_p", "presence_penalty", "frequency_penalty", "logprobs", "top_logprobs", "logit_bias"}) {
            root.remove(field);
        }
        String effort = config.reasoningEffort == null ? "" : config.reasoningEffort.trim();
        if (effort.isEmpty() || "keep".equalsIgnoreCase(effort) || root.has("reasoning_effort")) return;
        // gpt-5.6 on /v1/chat/completions only accepts function tools with reasoning_effort=none.
        boolean hasTools = root.has("tools") && root.get("tools").isJsonArray() && root.getAsJsonArray("tools").size() > 0;
        root.addProperty("reasoning_effort", hasTools ? "none" : effort);
    }

    private static final java.util.regex.Pattern EFFORT_VALUE =
            java.util.regex.Pattern.compile("'(none|minimal|low|medium|high|xhigh)'");

    /**
     * Picks the reasoning_effort to retry with from an upstream error message, preferring the cheapest value the
     * message names that differs from what was sent. Returns null when the message names no usable value.
     */
    static String retryEffort(String errorMessage, String sent) {
        java.util.Set<String> named = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = EFFORT_VALUE.matcher(errorMessage);
        while (m.find()) named.add(m.group(1));
        for (String candidate : new String[]{"none", "minimal", "low", "medium", "high", "xhigh"}) {
            if (named.contains(candidate) && !candidate.equals(sent)) return candidate;
        }
        return null;
    }

    /** Returns the body with a top-level string field set, or null when the body is not a JSON object. */
    static byte[] withField(byte[] body, String field, String value) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            root.addProperty(field, value);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String stringField(byte[] body, String field) {
        try {
            JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            return root.has(field) && root.get(field).isJsonPrimitive() ? root.get(field).getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Returns the JSON body without the given top-level field, or null when the body has no such field. */
    static byte[] withoutField(byte[] body, String field) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has(field)) return null;
            JsonObject root = parsed.getAsJsonObject();
            root.remove(field);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void rewriteContent(JsonObject msg) {
        if (!msg.has("content")) return;
        JsonElement content = msg.get("content");
        if (content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
            msg.addProperty("content", rewriteText(content.getAsString()));
        } else if (content.isJsonArray()) {
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")
                        && part.getAsJsonObject().get("text").isJsonPrimitive()) {
                    JsonObject p = part.getAsJsonObject();
                    p.addProperty("text", rewriteText(p.get("text").getAsString()));
                }
            }
        }
    }

    /**
     * Rewrites a multipart/form-data transcription request: forces the language field and (optionally) the model.
     * Returns null when the body cannot be parsed, so the caller can forward it unchanged.
     */
    public byte[] rewriteTranscription(byte[] body, String contentType) {
        String boundary = boundaryOf(contentType);
        if (boundary == null) return null;
        List<Part> parts = parseMultipart(body, boundary);
        if (parts == null) return null;

        boolean hasLanguage = false;
        boolean hasModel = false;
        for (Part part : parts) {
            if ("language".equals(part.name)) {
                hasLanguage = true;
                if (!config.sttLanguage.isBlank()) part.data = config.sttLanguage.getBytes(StandardCharsets.UTF_8);
            } else if ("model".equals(part.name)) {
                hasModel = true;
                if (!config.sttModel.isBlank()) part.data = config.sttModel.getBytes(StandardCharsets.UTF_8);
            }
        }
        if (!hasLanguage && !config.sttLanguage.isBlank()) parts.add(Part.field("language", config.sttLanguage));
        if (!hasModel && !config.sttModel.isBlank()) parts.add(Part.field("model", config.sttModel));
        return buildMultipart(parts, boundary);
    }

    /**
     * Turns a multipart/form-data transcription request into a chat/completions body carrying the audio, for upstreams
     * with no audio/transcriptions endpoint. Returns null when the body has no audio file.
     */
    public String transcriptionAsChat(byte[] body, String contentType) {
        String boundary = boundaryOf(contentType);
        if (boundary == null) return null;
        List<Part> parts = parseMultipart(body, boundary);
        if (parts == null) return null;
        Part file = null;
        for (Part part : parts) {
            if ("file".equals(part.name)) file = part;
        }
        if (file == null) return null;

        String language = config.sttLanguage.isBlank() ? "" : " The language is " + config.sttLanguage + ".";
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", "Transcribe the speech in this audio." + language
                + " Output only the words that were spoken, with no comments. If there is no speech, output nothing.");
        JsonObject audio = new JsonObject();
        audio.addProperty("data", java.util.Base64.getEncoder().encodeToString(file.data));
        audio.addProperty("format", audioFormatOf(file.headers));
        JsonObject audioPart = new JsonObject();
        audioPart.addProperty("type", "input_audio");
        audioPart.add("input_audio", audio);
        JsonArray content = new JsonArray();
        content.add(text);
        content.add(audioPart);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(user);
        JsonObject root = new JsonObject();
        root.addProperty("model", config.sttChatModel());
        root.addProperty("temperature", 0);
        root.add("messages", messages);
        return root.toString();
    }

    private static String audioFormatOf(String headers) {
        Matcher m = java.util.regex.Pattern.compile("filename=\"[^\"]*\\.([A-Za-z0-9]+)\"").matcher(headers);
        return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : "wav";
    }

    // Gemini sometimes spaces out Japanese transcripts ("お腹 空い た 。"). Spaces next to Latin words are kept.
    private static final java.util.regex.Pattern JAPANESE_SPACE = java.util.regex.Pattern.compile(
            "(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー、。])[ \\u3000]+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー、。！？])");

    /** Turns a chat/completions reply into the {"text": ...} body of audio/transcriptions, or null when it is not one. */
    static byte[] transcriptFromChat(byte[] chatReply) {
        try {
            JsonObject root = JsonParser.parseString(new String(chatReply, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject message = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
            boolean hasText = message.has("content") && message.get("content").isJsonPrimitive();
            JsonObject out = new JsonObject();
            out.addProperty("text", hasText ? JAPANESE_SPACE.matcher(message.get("content").getAsString().trim()).replaceAll("") : "");
            return out.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String boundaryOf(String contentType) {
        if (contentType == null) return null;
        for (String piece : contentType.split(";")) {
            String t = piece.trim();
            if (t.regionMatches(true, 0, "boundary=", 0, 9)) {
                String b = t.substring(9).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) b = b.substring(1, b.length() - 1);
                return b;
            }
        }
        return null;
    }

    static final class Part {
        String headers; // raw header block, without trailing blank line
        String name;
        byte[] data;

        static Part field(String name, String value) {
            Part p = new Part();
            p.name = name;
            p.headers = "Content-Disposition: form-data; name=\"" + name + "\"";
            p.data = value.getBytes(StandardCharsets.UTF_8);
            return p;
        }
    }

    static List<Part> parseMultipart(byte[] body, String boundary) {
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<Part> parts = new ArrayList<>();
        int pos = indexOf(body, delim, 0);
        if (pos < 0) return null;
        while (true) {
            int afterDelim = pos + delim.length;
            if (afterDelim + 1 < body.length && body[afterDelim] == '-' && body[afterDelim + 1] == '-') break;
            int headerStart = afterDelim + 2; // skip CRLF
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), headerStart);
            if (headerEnd < 0) return null;
            int dataStart = headerEnd + 4;
            int next = indexOf(body, ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1), dataStart);
            if (next < 0) return null;
            Part part = new Part();
            part.headers = new String(body, headerStart, headerEnd - headerStart, StandardCharsets.ISO_8859_1);
            part.name = nameOf(part.headers);
            part.data = java.util.Arrays.copyOfRange(body, dataStart, next);
            parts.add(part);
            pos = next + 2;
        }
        return parts;
    }

    private static String nameOf(String headers) {
        Matcher m = java.util.regex.Pattern.compile("name=\"([^\"]*)\"").matcher(headers);
        return m.find() ? m.group(1) : "";
    }

    static byte[] buildMultipart(List<Part> parts, String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
        for (Part part : parts) {
            out.writeBytes(("--" + boundary).getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(crlf);
            out.writeBytes(part.headers.getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(crlf);
            out.writeBytes(crlf);
            out.writeBytes(part.data);
            out.writeBytes(crlf);
        }
        out.writeBytes(("--" + boundary + "--").getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(crlf);
        return out.toByteArray();
    }

    static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
