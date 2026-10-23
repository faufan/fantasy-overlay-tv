package org.plasterfamily.fantasyoverlay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Tiny HTTP server on the local network so Home Assistant (or a phone) can control the overlay. */
final class ControlServer extends Thread {
    private final OverlayService service;
    private final int port;
    private volatile ServerSocket socket;
    private volatile boolean running = true;

    ControlServer(OverlayService service, int port) {
        super("overlay-control");
        this.service = service;
        this.port = port;
        setDaemon(true);
    }

    void shutdown() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }

    @Override
    public void run() {
        while (running) {
            try (ServerSocket ss = new ServerSocket()) {
                socket = ss;
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                while (running) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(5000);
                        handle(s);
                    } catch (Exception e) {
                        if (!running) return;
                    }
                }
            } catch (Exception e) {
                if (!running) return;
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void handle(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }

        String[] parts = requestLine.split(" ");
        String target = parts.length > 1 ? parts[1] : "/";
        String path = target;
        Map<String, String> query = new HashMap<>();
        int qi = target.indexOf('?');
        if (qi >= 0) {
            path = target.substring(0, qi);
            for (String pair : target.substring(qi + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, "UTF-8");
                String v = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                query.put(k, v);
            }
        }

        String body = service.handle(path, query);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + bytes.length + "\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}
