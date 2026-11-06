package com.aperogames.jpaibridge.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Settings for the local bridge. Stored as a plain UTF-8 properties file so players can edit it by hand.
 */
public final class BridgeConfig {

    public static final String DEFAULT_INSTRUCTION =
            "[LANGUAGE: write every reply in natural, casual Japanese (日本語), including any JSON \"message\" field."
                    + " This overrides every other instruction in this request that names English, Spanish, Portuguese"
                    + " or any other language, and it applies whatever language the player writes in.]";

    /** Instruction texts shipped by older versions; files still holding one of these are upgraded automatically. */
    private static final List<String> OLD_DEFAULT_INSTRUCTIONS = List.of(
            "[LANGUAGE: always answer in natural, casual Japanese (日本語), whatever language you were told to use before"
                    + " and whatever language the player writes in. Never answer in English unless the player explicitly asks"
                    + " for English in this very message.]");

    public boolean enabled = true;
    public int port = 5010;

    public String chatUpstream = "https://api.groq.com/openai/v1/";
    public String chatKey = "";
    public String chatModel = "";
    /** reasoning_effort added for OpenAI reasoning models (gpt-5.x / o-series) when the mod sends none. "keep" = do not add. */
    public String reasoningEffort = "low";

    public String sttUpstream = "";
    public String sttKey = "";
    public String sttModel = "whisper-large-v3";
    public String sttLanguage = "ja";
    /** "auto" = send audio through chat/completions for Gemini, which has no audio/transcriptions; "chat" = always; "whisper" = never. */
    public String sttMode = "auto";

    public String instruction = DEFAULT_INSTRUCTION;
    public boolean addSystemMessage = true;
    public String lastUserSuffix = "";

    /** When true, the rewritten request and the start of each reply are written to the game log. */
    public boolean debug = false;

    /** Pairs of (regex, replacement). The replacement token {INSTRUCTION} is swapped for {@link #instruction}. */
    public final List<Rule> rules = new ArrayList<>();

    public record Rule(Pattern pattern, String replacement) {
    }

    /** When false, only the rules written in the file are used. */
    public boolean builtinRules = true;

    /** Rules from the file that are not built in. */
    public final List<Rule> extraRules = new ArrayList<>();

    public static BridgeConfig defaults() {
        BridgeConfig c = new BridgeConfig();
        c.rebuildRules();
        return c;
    }

    static List<Rule> builtinRuleList() {
        List<Rule> list = new ArrayList<>();
        // Language tags injected by AI companion mods ([LANGUAGE: ...] / [IDIOMA: ...]).
        list.add(new Rule(Pattern.compile("\\[LANGUAGE:[^\\]]*\\]"), "{INSTRUCTION}"));
        list.add(new Rule(Pattern.compile("\\[IDIOMA:[^\\]]*\\]"), "{INSTRUCTION}"));
        // Hard-coded "always answer in Spanish" sentences.
        list.add(new Rule(Pattern.compile("(?i)IMPORTANTE:[^.\\n]*SIEMPRE en español[^.\\n]*\\.(\\s*el campo \"message\"[^.\\n]*\\.)?"), ""));
        // Language lists such as "(Spanish, English or Brazilian Portuguese)".
        list.add(new Rule(Pattern.compile("\\((?:Spanish|English)[^)]*(?:English|Spanish|Portuguese)\\)"), "(Japanese)"));
        // Inline language requirements inside task prompts, e.g. "Write exactly one sentence in natural English".
        list.add(new Rule(Pattern.compile("(?i)\\b(?:in|into)\\s+(?:(?:natural|plain|simple|casual|conversational|fluent)\\s+)?English\\b"),
                "in natural Japanese (日本語)"));
        list.add(new Rule(Pattern.compile("(?i)\\ben\\s+español(?:\\s+latino)?(?:\\s+mexicano)?(?:\\s+natural)?"),
                "en japonés natural (日本語)"));
        list.add(new Rule(Pattern.compile("(?i)\\ben\\s+inglés(?:\\s+natural)?"), "en japonés natural (日本語)"));
        list.add(new Rule(Pattern.compile("(?i)\\bem\\s+português(?:\\s+brasileiro)?(?:\\s+natural)?"),
                "em japonês natural (日本語)"));
        return list;
    }

    private void rebuildRules() {
        rules.clear();
        if (builtinRules) rules.addAll(builtinRuleList());
        rules.addAll(extraRules);
    }

    public String sttUpstreamOrChat() {
        return sttUpstream == null || sttUpstream.isBlank() ? chatUpstream : sttUpstream;
    }

    public String sttKeyOrChat() {
        return sttKey == null || sttKey.isBlank() ? chatKey : sttKey;
    }

    public boolean sttViaChat() {
        if ("chat".equalsIgnoreCase(sttMode)) return true;
        if ("whisper".equalsIgnoreCase(sttMode)) return false;
        return sttUpstreamOrChat().contains("generativelanguage.googleapis.com");
    }

    /** Model used when {@link #sttViaChat()}: a whisper name cannot answer chat/completions, so fall back to the chat model. */
    public String sttChatModel() {
        return sttModel == null || sttModel.isBlank() || sttModel.contains("whisper") ? chatModel : sttModel;
    }

    public static BridgeConfig load(Path file) throws IOException {
        BridgeConfig c = new BridgeConfig();
        if (!Files.exists(file)) {
            c.rebuildRules();
            c.save(file);
            return c;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        c.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
        c.port = Integer.parseInt(p.getProperty("port", "5010").trim());
        c.chatUpstream = p.getProperty("chat.upstream", c.chatUpstream).trim();
        c.chatKey = p.getProperty("chat.key", "").trim();
        c.chatModel = p.getProperty("chat.model", "").trim();
        c.reasoningEffort = p.getProperty("chat.reasoningEffort", c.reasoningEffort).trim();
        c.sttUpstream = p.getProperty("stt.upstream", "").trim();
        c.sttKey = p.getProperty("stt.key", "").trim();
        c.sttModel = p.getProperty("stt.model", c.sttModel).trim();
        c.sttLanguage = p.getProperty("stt.language", c.sttLanguage).trim();
        c.sttMode = p.getProperty("stt.mode", c.sttMode).trim();
        c.instruction = p.getProperty("instruction", DEFAULT_INSTRUCTION);
        if (OLD_DEFAULT_INSTRUCTIONS.contains(c.instruction)) c.instruction = DEFAULT_INSTRUCTION;
        c.addSystemMessage = Boolean.parseBoolean(p.getProperty("instruction.addSystemMessage", "true"));
        c.lastUserSuffix = p.getProperty("instruction.lastUserSuffix", "");
        c.debug = Boolean.parseBoolean(p.getProperty("debug", "false").trim());
        c.builtinRules = Boolean.parseBoolean(p.getProperty("rules.builtin", "true").trim());
        List<String> builtinPatterns = builtinRuleList().stream().map(r -> r.pattern().pattern()).toList();
        for (int i = 1; i <= 50; i++) {
            String regex = p.getProperty("rule." + i + ".regex");
            if (regex == null || builtinPatterns.contains(regex)) continue; // older files saved the built-ins
            c.extraRules.add(new Rule(Pattern.compile(regex), p.getProperty("rule." + i + ".replace", "")));
        }
        c.rebuildRules();
        return c;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# JP AI Bridge settings (UTF-8).\n");
        sb.append("# Point an AI mod's \"local / custom server\" address at http://127.0.0.1:<port>/v1/\n");
        sb.append("# The bridge rewrites language instructions to Japanese and forwards to the upstream API.\n");
        sb.append("# Keys are stored here in plain text: do not share this file.\n\n");
        sb.append("enabled=").append(enabled).append('\n');
        sb.append("port=").append(port).append('\n');
        sb.append("# true = write the rewritten prompt and the start of each reply to logs/latest.log (for troubleshooting)\n");
        sb.append("debug=").append(debug).append("\n\n");
        sb.append("# Conversation (OpenAI-compatible). Examples: https://api.groq.com/openai/v1/  https://api.openai.com/v1/\n");
        sb.append("chat.upstream=").append(chatUpstream).append('\n');
        sb.append("chat.key=").append(chatKey).append('\n');
        sb.append("# Leave blank to keep the model name sent by the mod.\n");
        sb.append("chat.model=").append(chatModel).append('\n');
        sb.append("# OpenAI reasoning models (gpt-5.x / o-series): effort to use when the mod sends none (low/medium/high, keep = leave as is; unsupported values are dropped automatically)\n");
        sb.append("chat.reasoningEffort=").append(reasoningEffort).append("\n\n");
        sb.append("# Speech-to-text. Blank upstream/key = same as chat.\n");
        sb.append("stt.upstream=").append(sttUpstream).append('\n');
        sb.append("stt.key=").append(sttKey).append('\n');
        sb.append("stt.model=").append(sttModel).append('\n');
        sb.append("stt.language=").append(sttLanguage).append('\n');
        sb.append("# auto = Gemini gets the audio through chat/completions (it has no audio/transcriptions), using chat.model unless stt.model is a gemini- name.\n");
        sb.append("# chat = always do that. whisper = never do that.\n");
        sb.append("stt.mode=").append(sttMode).append("\n\n");
        sb.append("instruction=").append(escape(instruction)).append('\n');
        sb.append("instruction.addSystemMessage=").append(addSystemMessage).append('\n');
        sb.append("instruction.lastUserSuffix=").append(escape(lastUserSuffix)).append("\n\n");
        sb.append("# Built-in rewrite rules (English/Spanish/Portuguese language requirements -> Japanese).\n");
        sb.append("rules.builtin=").append(builtinRules).append("\n\n");
        sb.append("# Extra rewrite rules applied to every message text. {INSTRUCTION} = the instruction above.\n");
        sb.append("# Example:\n# rule.1.regex=(?i)answer in French\n# rule.1.replace=answer in Japanese\n");
        int i = 1;
        for (Rule rule : extraRules) {
            sb.append("rule.").append(i).append(".regex=").append(escape(rule.pattern().pattern())).append('\n');
            sb.append("rule.").append(i).append(".replace=").append(escape(rule.replacement())).append('\n');
            i++;
        }
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '=' -> out.append("\\=");
                case ':' -> out.append("\\:");
                case '#' -> out.append("\\#");
                case '!' -> out.append("\\!");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }
}
