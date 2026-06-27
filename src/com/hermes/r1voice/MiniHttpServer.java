package com.hermes.r1voice;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

public class MiniHttpServer {
    private ServerSocket serverSocket;
    private int port;
    private RequestHandler handler;
    private volatile boolean running;

    public interface RequestHandler {
        String handle(String path, String method, String body) throws Exception;
    }

    public MiniHttpServer(int port, RequestHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    public void start() {
        running = true;
        new Thread() {
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                    while (running) {
                        Socket client = serverSocket.accept();
                        new Thread(new ClientHandler(client)).start();
                    }
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                }
            }
        }.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
    }

    class ClientHandler implements Runnable {
        Socket client;
        ClientHandler(Socket c) { this.client = c; }

        public void run() {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String requestLine = br.readLine();
                if (requestLine == null) { client.close(); return; }
                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts[1];

                // Read headers
                int contentLength = 0;
                String line;
                while ((line = br.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }

                // Read body
                String body = "";
                if (contentLength > 0) {
                    char[] buf = new char[contentLength];
                    int read = 0;
                    while (read < contentLength) {
                        int n = br.read(buf, read, contentLength - read);
                        if (n < 0) break;
                        read += n;
                    }
                    body = new String(buf, 0, read);
                }

                // Handle request
                String response = handler.handle(path, method, body);
                byte[] respBytes = response.getBytes("UTF-8");

                // Send response
                PrintWriter pw = new PrintWriter(client.getOutputStream());
                pw.print("HTTP/1.1 200 OK\r\n");
                pw.print("Content-Type: text/html; charset=utf-8\r\n");
                pw.print("Content-Length: " + respBytes.length + "\r\n");
                pw.print("Access-Control-Allow-Origin: *\r\n");
                pw.print("\r\n");
                pw.flush();
                client.getOutputStream().write(respBytes);
                client.getOutputStream().flush();
                client.close();
            } catch (Exception e) {
                try { client.close(); } catch (Exception ex) {}
            }
        }
    }

    public static Map<String, String> parseFormData(String data) {
        Map<String, String> map = new HashMap<>();
        if (data == null || data.isEmpty()) return map;
        for (String pair : data.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
                } catch (Exception e) {}
            }
        }
        return map;
    }
}
