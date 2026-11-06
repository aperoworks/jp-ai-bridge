package com.aperogames.jpaibridge.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Minimal local HTTP server (127.0.0.1 only) that forwards OpenAI-compatible requests to an upstream API,
 * rewriting language instructions on the way.
 */
public final class BridgeServer {

    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "host", "content-length", "connection", "upgrade", "http2-settings", "authorization",
            "transfer-encoding", "expect", "keep-alive", "te", "accept-encoding");

    private final BridgeConfig config;
    private final Rewriter rewriter;
    private final Consumer<String> log;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "jp-ai-bridge");
        t.setDaemon(true);
        return t;
    });
    private final List<ServerSocket> servers = new ArrayList<>();

    public BridgeServer(BridgeConfig config, Consumer<String> log) {
        this.config = config;
        this.rewriter = new Rewriter(config);
        this.log = log;
    }

    /**
     * Listens on IPv4 127.0.0.1 and, when available, IPv6 ::1. Binding explicitly matters: the Minecraft launcher
     * starts Java with java.net.preferIPv6Addresses=system, which can make getLoopbackAddress() return ::1 only.
     */
    public void start() throws IOException {
        IOException firstFailure = null;
        for (String host : new String[]{"127.0.0.1", "::1"}) {
            try {
                ServerSocket socket = new ServerSocket(config.port, 50, InetAddress.getByName(host));
                servers.add(socket);
                Thread acceptor = new Thread(() -> acceptLoop(socket), "jp-ai-bridge-accept-" + host);
                acceptor.setDaemon(true);
                acceptor.start();
                log.accept("JP AI Bridge listening on " + socket.getLocalSocketAddress() + " -> " + config.chatUpstream);
            } catch (IOException e) {
                if (firstFailure == null) firstFailure = e;
                log.accept("JP AI Bridge could not listen on " + host + ":" + config.port + " (" + e.getMessage() + ")");
            }
        }
        if (servers.isEmpty()) throw firstFailure;
    }

    public void stop() {
        for (ServerSocket socket : servers) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    private void acceptLoop(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!server.isClosed()) log.accept("accept failed: " + e);
            }
        }
    }

    private record Request(String method, String path, Map<String, String> headers, byte[] body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private void handle(Socket socket) {
        log.accept("connection from " + socket.getRemoteSocketAddress());
        try (socket) {
            socket.setSoTimeout(120_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            Request req = readRequest(in, out);
            if (req == null) return;
            forward(req, out);
        } catch (Exception e) {
            log.accept("request failed: " + e);
        }
    }

    private void forward(Request req, OutputStream out) throws IOException {
        String path = req.path();
        String relative = relativePath(path);
        boolean isChat = relative.startsWith("chat/completions");
        boolean isStt = relative.startsWith("audio/transcriptions") || relative.startsWith("audio/translations");

        String base = isStt ? config.sttUpstreamOrChat() : config.chatUpstream;
        String key = isStt ? config.sttKeyOrChat() : config.chatKey;
        if (!base.endsWith("/")) base = base + "/";
        String target = base + relative;

        byte[] body = req.body();
        String contentType = req.header("content-type");
        if (config.enabled && isStt && config.sttViaChat() && body.length > 0) {
            String chat = rewriter.transcriptionAsChat(body, contentType);
            if (chat != null) {
                transcribeViaChat(req, out, base + "chat/completions", key, chat);
                return;
            }
        }
        if (config.enabled && isChat && body.length > 0) {
            String rewritten = rewriter.rewriteChat(new String(body, StandardCharsets.UTF_8));
            body = rewritten.getBytes(StandardCharsets.UTF_8);
            log.accept("chat -> " + target + " (language rewritten)");
            if (config.debug) log.accept("chat request: " + clip(rewritten, 8000));
        } else if (config.enabled && isStt && body.length > 0) {
            byte[] rewritten = rewriter.rewriteTranscription(body, contentType);
            if (rewritten != null) body = rewritten;
            log.accept("transcription -> " + target + " (language=" + config.sttLanguage + ")");
        } else {
            log.accept(req.method() + " " + relative + " -> " + target);
        }

        HttpResponse<byte[]> resp;
        try {
            resp = send(req, target, key, body);
            // Models differ in which reasoning_effort values they accept (and gpt-5.6 needs 'none' with tools).
            // Retry with the value the error message suggests, then without the field as a last resort.
            if (isChat && resp.statusCode() == 400 && mentionsReasoningEffort(resp.body())) {
                String sent = Rewriter.stringField(body, "reasoning_effort");
                String next = Rewriter.retryEffort(new String(resp.body(), StandardCharsets.UTF_8), sent);
                byte[] retry = next != null ? Rewriter.withField(body, "reasoning_effort", next) : null;
                if (retry != null) {
                    log.accept("upstream rejected reasoning_effort=" + sent + "; retrying with " + next);
                    resp = send(req, target, key, retry);
                }
                if (resp.statusCode() == 400 && mentionsReasoningEffort(resp.body())) {
                    byte[] stripped = Rewriter.withoutField(body, "reasoning_effort");
                    if (stripped != null) {
                        log.accept("upstream still rejects reasoning_effort; retrying without it");
                        resp = send(req, target, key, stripped);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeResponse(out, 502, "application/json", error("bridge interrupted"));
            return;
        } catch (IOException e) {
            log.accept("upstream unreachable: " + e);
            writeResponse(out, 502, "application/json", error("upstream unreachable: " + e.getMessage()));
            return;
        }
        if (resp.statusCode() >= 400) {
            String snippet = new String(resp.body(), StandardCharsets.UTF_8);
            log.accept("upstream " + resp.statusCode() + ": " + snippet.substring(0, Math.min(300, snippet.length())));
        } else if (isChat) {
            String reply = new String(resp.body(), StandardCharsets.UTF_8);
            log.accept("tokens: " + usageOf(reply));
            if (config.debug) log.accept("chat reply: " + clip(reply, 1500));
        }
        String respType = resp.headers().firstValue("content-type").orElse("application/json");
        writeResponse(out, resp.statusCode(), respType, resp.body());
    }

    /** For upstreams with no audio/transcriptions: asks chat/completions and answers the mod in the transcription format. */
    private void transcribeViaChat(Request req, OutputStream out, String target, String key, String chat) throws IOException {
        log.accept("transcription -> " + target + " (audio in chat, language=" + config.sttLanguage + ")");
        HttpResponse<byte[]> resp;
        try {
            resp = send(req, target, key, chat.getBytes(StandardCharsets.UTF_8), "application/json");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeResponse(out, 502, "application/json", error("bridge interrupted"));
            return;
        } catch (IOException e) {
            log.accept("upstream unreachable: " + e);
            writeResponse(out, 502, "application/json", error("upstream unreachable: " + e.getMessage()));
            return;
        }
        String reply = new String(resp.body(), StandardCharsets.UTF_8);
        if (resp.statusCode() >= 400) {
            log.accept("upstream " + resp.statusCode() + ": " + reply.substring(0, Math.min(300, reply.length())));
            writeResponse(out, resp.statusCode(), "application/json", resp.body());
            return;
        }
        log.accept("tokens: " + usageOf(reply));
        byte[] transcript = Rewriter.transcriptFromChat(resp.body());
        if (transcript == null) {
            log.accept("unexpected transcription reply: " + clip(reply, 300));
            writeResponse(out, 502, "application/json", error("unexpected transcription reply"));
            return;
        }
        if (config.debug) log.accept("transcript: " + new String(transcript, StandardCharsets.UTF_8));
        writeResponse(out, 200, "application/json", transcript);
    }

    private HttpResponse<byte[]> send(Request req, String target, String key, byte[] body)
            throws IOException, InterruptedException {
        return send(req, target, key, body, null);
    }

    private HttpResponse<byte[]> send(Request req, String target, String key, byte[] body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(90));
        req.headers().forEach((name, value) -> {
            if (!SKIP_REQUEST_HEADERS.contains(name)) {
                try {
                    b.header(name, value);
                } catch (IllegalArgumentException ignored) {
                    // restricted header
                }
            }
        });
        if (contentType != null) b.setHeader("Content-Type", contentType);
        if (key != null && !key.isBlank()) {
            b.header("Authorization", "Bearer " + key);
        } else if (req.header("authorization") != null) {
            b.header("Authorization", req.header("authorization"));
        }
        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        b.method(req.method(), publisher);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static boolean mentionsReasoningEffort(byte[] responseBody) {
        return new String(responseBody, StandardCharsets.UTF_8).contains("reasoning_effort");
    }

    /** "/v1/chat/completions" -> "chat/completions"; "/chat/completions" -> "chat/completions". */
    static String relativePath(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.startsWith("v1/")) p = p.substring(3);
        return p;
    }

    static String usageOf(String json) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("usage") || !root.get("usage").isJsonObject()) return "not reported";
            com.google.gson.JsonObject u = root.getAsJsonObject("usage");
            return "prompt=" + num(u, "prompt_tokens") + " completion=" + num(u, "completion_tokens")
                    + " total=" + num(u, "total_tokens");
        } catch (RuntimeException e) {
            return "not reported";
        }
    }

    private static String num(com.google.gson.JsonObject o, String name) {
        return o.has(name) && o.get(name).isJsonPrimitive() ? o.get(name).getAsString() : "?";
    }

    private static String clip(String s, int max) {
        String oneLine = s.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...(" + oneLine.length() + " chars)";
    }

    private static byte[] error(String message) {
        return ("{\"error\":{\"message\":\"" + message.replace("\"", "'") + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            default -> "Status";
        };
    }

    private static Request readRequest(InputStream in, OutputStream out) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        if ("100-continue".equalsIgnoreCase(headers.get("expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
        byte[] body;
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunked(in);
        } else {
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0").trim());
            body = in.readNBytes(length);
        }
        return new Request(parts[0], parts[1], headers, body);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
            if (size == 0) {
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    // ignore trailers
                }
                break;
            }
            out.writeBytes(in.readNBytes(size));
            readLine(in);
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.ISO_8859_1);
    }
}
