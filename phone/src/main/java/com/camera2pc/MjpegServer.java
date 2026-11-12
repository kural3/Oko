package com.camera2pc;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Простой HTTP-сервер, отдающий MJPEG-поток (multipart/x-mixed-replace).
 *   /video  — сам поток (его читает программа на ПК)
 *   /       — HTML-страница для просмотра в браузере
 */
public class MjpegServer {
    public static final String BOUNDARY = "camera2pcframe";

    private final int port;
    private volatile boolean running;
    private ServerSocket serverSocket;

    private final Object lock = new Object();
    private byte[] frame;
    private long frameId;
    private int rotation;
    private final AtomicInteger clients = new AtomicInteger();
    private final Api api;
    /** Вызывается (в потоке сервера), когда подключается или отключается зритель. */
    public volatile Runnable clientsListener;

    /** Управление камерой: /api/state и /api/set?key=value. Ответ — строки key=value. */
    public interface Api {
        String handle(String path, Map<String, String> query);
    }

    public MjpegServer(int port, Api api) {
        this.port = port;
        this.api = api;
    }

    public int getPort() {
        return port;
    }

    public int getClientCount() {
        return clients.get();
    }

    public boolean hasClients() {
        return clients.get() > 0;
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::acceptLoop, "mjpeg-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Публикует новый JPEG-кадр всем подключённым клиентам. */
    public void publish(byte[] jpeg, int rotationDegrees) {
        synchronized (lock) {
            frame = jpeg;
            rotation = rotationDegrees;
            frameId++;
            lock.notifyAll();
        }
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            while (running) {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> handle(s), "mjpeg-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            // сервер остановлен
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // пропускаем заголовки
            }
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";

            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            if (path.startsWith("/video")) {
                streamVideo(socket, out);
            } else if (path.startsWith("/api/")) {
                int q = path.indexOf('?');
                String route = q >= 0 ? path.substring(0, q) : path;
                Map<String, String> query = new LinkedHashMap<>();
                if (q >= 0) {
                    for (String kv : path.substring(q + 1).split("&")) {
                        if (kv.isEmpty()) continue;
                        int e = kv.indexOf('=');
                        String k = URLDecoder.decode(e >= 0 ? kv.substring(0, e) : kv, "UTF-8");
                        String v = e >= 0 ? URLDecoder.decode(kv.substring(e + 1), "UTF-8") : "";
                        query.put(k, v);
                    }
                }
                String text = api != null ? api.handle(route, query) : "";
                byte[] body = text.getBytes(StandardCharsets.UTF_8);
                writeTextHeader(out, body.length);
                out.write(body);
                out.flush();
            } else {
                String html = "<!doctype html><html><head><meta charset='utf-8'>"
                        + "<meta name='viewport' content='width=device-width'>"
                        + "<title>Oko</title></head>"
                        + "<body style='margin:0;background:#000'>"
                        + "<img src='/video' style='width:100%;height:100vh;object-fit:contain'>"
                        + "</body></html>";
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeTextHeader(OutputStream out, int length) throws IOException {
        String crlf = "\r\n";
        String h = "HTTP/1.1 200 OK" + crlf
                + "Content-Type: text/plain; charset=utf-8" + crlf
                + "Content-Length: " + length + crlf
                + "Connection: close" + crlf + crlf;
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void streamVideo(Socket socket, OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                + "Cache-Control: no-cache, no-store\r\n"
                + "Pragma: no-cache\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        socket.setSoTimeout(0);

        clients.incrementAndGet();
        Runnable listener = clientsListener;
        if (listener != null) listener.run();
        try {
            long lastSent = -1;
            while (running) {
                byte[] data;
                int rot;
                synchronized (lock) {
                    while (running && frameId == lastSent) {
                        try {
                            lock.wait(1000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (!running) return;
                    data = frame;
                    rot = rotation;
                    lastSent = frameId;
                }
                if (data == null) continue;
                String header = "--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + data.length + "\r\n"
                        + "X-Rotation: " + rot + "\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.ISO_8859_1));
                out.write(data);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } finally {
            clients.decrementAndGet();
            Runnable l = clientsListener;
            if (l != null) l.run();
        }
    }
}
