package fr.arbres;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Petit serveur web intégré qui distribue le pack de textures aux joueurs. */
final class PackHost {
    static final String PATH = "/ArbresPack.zip";

    private final HttpServer server;
    private final ExecutorService pool;
    private final String sha1;

    private PackHost(HttpServer server, ExecutorService pool, String sha1) {
        this.server = server;
        this.pool = pool;
        this.sha1 = sha1;
    }

    static PackHost start(byte[] data, int port) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest(data)) hex.append(String.format("%02x", b));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Arbres-PackHost");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext(PATH, (HttpExchange ex) -> {
            try {
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                    ex.getResponseHeaders().add("Content-Length", String.valueOf(data.length));
                    ex.sendResponseHeaders(200, -1);
                } else {
                    ex.sendResponseHeaders(200, data.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(data);
                    }
                }
            } catch (IOException ignored) {
                // le joueur a coupé la connexion
            } finally {
                ex.close();
            }
        });
        server.start();
        return new PackHost(server, pool, hex.toString());
    }

    String sha1() {
        return sha1;
    }

    void stop() {
        server.stop(0);
        pool.shutdownNow();
    }
}
