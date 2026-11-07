package com.moniptv.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Petit serveur HTTP local (127.0.0.1 uniquement). Equivalent Java de server.js :
 * sert l'interface et relaie les requetes IPTV (contourne CORS / HTTP / certificats).
 */
public class LocalServer {

    public interface Assets {
        InputStream open(String name) throws IOException;
    }

    private static final int PREFERRED_PORT = 38517;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    private final Assets assets;
    private ServerSocket socket;
    private ExecutorService pool;
    private volatile boolean running;
    private SSLSocketFactory trustAllFactory;
    private HostnameVerifier anyHost;

    public LocalServer(Assets assets) {
        this.assets = assets;
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            trustAllFactory = ctx.getSocketFactory();
            anyHost = new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            };
        } catch (Exception e) {
            trustAllFactory = null;
        }
    }

    public int getPort() {
        return socket == null ? -1 : socket.getLocalPort();
    }

    public void start() throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try {
            socket = new ServerSocket(PREFERRED_PORT, 50, loopback);
        } catch (IOException e) {
            socket = new ServerSocket(0, 50, loopback);
        }
        running = true;
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "iptv-conn");
                t.setDaemon(true);
                return t;
            }
        });
        Thread acceptor = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket c = socket.accept();
                        pool.execute(new Runnable() {
                            public void run() { handle(c); }
                        });
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "iptv-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    // ------------------------------------------------------------------

    private void handle(Socket c) {
        try {
            c.setSoTimeout(30000);
            c.setTcpNoDelay(true);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String range = null;
            String acceptEnc = "";
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int i = line.indexOf(':');
                if (i < 0) continue;
                String k = line.substring(0, i).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(i + 1).trim();
                if (k.equals("range")) range = v;
                else if (k.equals("accept-encoding")) acceptEnc = v.toLowerCase(Locale.ROOT);
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !parts[0].equals("GET")) {
                simple(out, 405, "Methode refusee");
                return;
            }
            String path = parts[1];
            int q = path.indexOf('?');
            String route = q < 0 ? path : path.substring(0, q);
            String query = q < 0 ? "" : path.substring(q + 1);

            if (route.equals("/") || route.equals("/index.html")) {
                asset(out, "index.html", "text/html; charset=utf-8");
            } else if (route.equals("/hls.min.js")) {
                asset(out, "hls.min.js", "application/javascript; charset=utf-8");
            } else if (route.equals("/proxy")) {
                String target = queryParam(query, "url");
                if (target == null || target.length() == 0) {
                    simple(out, 400, "url manquante");
                } else {
                    relay(out, target, range, acceptEnc.contains("gzip"));
                }
            } else {
                simple(out, 404, "Introuvable");
            }
        } catch (IOException ignored) {
            // client parti (changement de chaine, fermeture du lecteur...)
        } catch (RuntimeException ignored) {
        } finally {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    private static String queryParam(String query, String name) {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(name)) {
                try {
                    return URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 16384) throw new IOException("ligne trop longue");
        }
        if (ch == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), UTF8);
    }

    private void asset(OutputStream out, String name, String type) throws IOException {
        byte[] data;
        try {
            InputStream is = assets.open(name);
            try { data = readAll(is); } finally { is.close(); }
        } catch (IOException e) {
            simple(out, 404, "Introuvable");
            return;
        }
        writeHead(out, 200, type, String.valueOf(data.length), null, null, null, true);
        out.write(data);
        out.flush();
    }

    private void simple(OutputStream out, int code, String msg) throws IOException {
        byte[] b = msg.getBytes(UTF8);
        writeHead(out, code, "text/plain; charset=utf-8", String.valueOf(b.length), null, null, null, true);
        out.write(b);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 502: return "Bad Gateway";
            default: return "Status";
        }
    }

    private static void writeHead(OutputStream out, int code, String type, String length,
                                  String contentRange, String acceptRanges, String encoding,
                                  boolean noStore) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        if (length != null) h.append("Content-Length: ").append(length).append("\r\n");
        if (contentRange != null) h.append("Content-Range: ").append(contentRange).append("\r\n");
        if (acceptRanges != null) h.append("Accept-Ranges: ").append(acceptRanges).append("\r\n");
        if (encoding != null) h.append("Content-Encoding: ").append(encoding).append("\r\n");
        if (noStore) h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(UTF8));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return b.toByteArray();
    }

    // ------------------------------------------------------------------

    private void relay(OutputStream out, String target, String range, boolean clientGzip) throws IOException {
        for (int hops = 0; hops <= 5; hops++) {
            URL u;
            try {
                u = new URL(target);
            } catch (MalformedURLException e) {
                simple(out, 400, "URL invalide");
                return;
            }
            String proto = u.getProtocol();
            if (!proto.equals("http") && !proto.equals("https")) {
                simple(out, 400, "Protocole refuse");
                return;
            }

            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            if (c instanceof HttpsURLConnection && trustAllFactory != null) {
                ((HttpsURLConnection) c).setSSLSocketFactory(trustAllFactory);
                ((HttpsURLConnection) c).setHostnameVerifier(anyHost);
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (IPTV-Web)");
            c.setRequestProperty("Accept-Encoding", (range == null && clientGzip) ? "gzip" : "identity");
            if (range != null) c.setRequestProperty("Range", range);

            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                simple(out, 502, "Erreur serveur IPTV : " + e.getMessage());
                return;
            }

            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                if (loc != null && hops < 5) {
                    c.disconnect();
                    try {
                        target = new URL(u, loc).toString();
                    } catch (MalformedURLException e) {
                        simple(out, 502, "Redirection invalide");
                        return;
                    }
                    continue;
                }
            }

            try {
                stream(out, c, u, code);
            } finally {
                c.disconnect();
            }
            return;
        }
        simple(out, 502, "Trop de redirections");
    }

    private void stream(OutputStream out, HttpURLConnection c, URL u, int code) throws IOException {
        InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String ct = c.getContentType();
        String enc = c.getContentEncoding();
        boolean gz = enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip");
        boolean playlist = code < 400 && body != null
                && ((ct != null && ct.toLowerCase(Locale.ROOT).contains("mpegurl"))
                || u.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8"));

        if (playlist) {
            InputStream src = gz ? new GZIPInputStream(body) : body;
            byte[] data = readAll(src);
            byte[] rewritten = rewritePlaylist(new String(data, UTF8), u.toString()).getBytes(UTF8);
            writeHead(out, code, "application/vnd.apple.mpegurl", String.valueOf(rewritten.length),
                    null, null, null, true);
            out.write(rewritten);
            out.flush();
            return;
        }

        writeHead(out, code, ct, c.getHeaderField("Content-Length"), c.getHeaderField("Content-Range"),
                c.getHeaderField("Accept-Ranges"), gz ? "gzip" : null, false);
        if (body == null) {
            out.flush();
            return;
        }
        byte[] buf = new byte[32768];
        int n;
        while ((n = body.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    // ------------------------------------------------------------------

    static String proxied(String abs) {
        try {
            return "/proxy?url=" + URLEncoder.encode(abs, "UTF-8");
        } catch (Exception e) {
            return abs;
        }
    }

    static String rewritePlaylist(String text, String base) {
        String[] lines = text.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder();
        URL baseUrl;
        try {
            baseUrl = new URL(base);
        } catch (MalformedURLException e) {
            return text;
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            String res;
            if (t.length() == 0) {
                res = line;
            } else if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer b = new StringBuffer();
                while (m.find()) {
                    String rep;
                    try {
                        rep = "URI=\"" + proxied(new URL(baseUrl, m.group(1)).toString()) + "\"";
                    } catch (MalformedURLException e) {
                        rep = m.group(0);
                    }
                    m.appendReplacement(b, Matcher.quoteReplacement(rep));
                }
                m.appendTail(b);
                res = b.toString();
            } else {
                try {
                    res = proxied(new URL(baseUrl, t).toString());
                } catch (MalformedURLException e) {
                    res = line;
                }
            }
            sb.append(res);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
