package org.itonkung.scautoauction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Crash-resistant local price cache, plus optional authenticated remote sync.
 * Secrets live only in config/easylife-remote.json on the player's own machine.
 * No credential is packaged into a public jar, included in logs or committed.
 */
public final class PriceArchive {
    private static final Path CACHE = FabricLoader.getInstance().getConfigDir()
        .resolve("easylife-price-cache.json");
    private static final Path REMOTE_CONFIG = FabricLoader.getInstance().getConfigDir()
        .resolve("easylife-remote.json");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static volatile String lastStored = "";
    private static volatile String lastSent = "";
    private static volatile long lastLocalAttempt;
    private static volatile long lastRemoteAttempt;
    private static volatile String syncStatus = "รอการตั้งค่าเว็บ";
    private PriceArchive() {}

    public static String getSyncStatus() { return syncStatus; }

    public static String load() {
        try {
            if (!Files.isRegularFile(CACHE)) return "[]";
            String data = Files.readString(CACHE, StandardCharsets.UTF_8);
            if (data.length() > 1_000_000) throw new IOException("Too many cached items");
            // Do not trust stale or partial JSON files on disk.
            if (!JsonParser.parseString(data).isJsonArray()) throw new IOException("Not an array");
            lastStored = data;
            return data;
        } catch (Exception ex) {
            System.err.println("[Easylife] Cache read failed: " + ex.getClass().getSimpleName());
            return "[]";
        }
    }

    /**
     * May be called on the render/client tick; never blocks the game thread.
     * Local snapshots are saved every ~2s, cloud uploads at most every 30s.
     */
    public static void saveEventually(String snapshot) {
        if (snapshot == null || snapshot.length() > 1_000_000 || snapshot.length() < 2) return;
        long now = System.currentTimeMillis();
        boolean localDue = !snapshot.equals(lastStored) && now - lastLocalAttempt >= 2_000;
        boolean remoteDue = !snapshot.equals(lastSent) && now - lastRemoteAttempt >= 30_000;
        if (!localDue && !remoteDue) return;
        if (!BUSY.compareAndSet(false, true)) return;
        if (localDue) lastLocalAttempt = now;
        if (remoteDue) lastRemoteAttempt = now;
        Thread.startVirtualThread(() -> {
            try {
                if (localDue) saveLocal(snapshot);
                if (remoteDue) upload(snapshot);
            } finally {
                BUSY.set(false);
            }
        });
    }

    public static void saveLocal(String snapshot) {
        try {
            Files.createDirectories(CACHE.getParent());
            Path tmp = Files.createTempFile(CACHE.getParent(), "easylife-price-", ".json.tmp");
            try {
                Files.writeString(tmp, snapshot, StandardCharsets.UTF_8);
                Files.move(tmp, CACHE, StandardCopyOption.REPLACE_EXISTING);
                lastStored = snapshot;
            } finally { Files.deleteIfExists(tmp); }
        } catch (IOException ex) {
            System.err.println("[Easylife] Unable to persist price cache: " + ex.getClass().getSimpleName());
        }
    }

    private static void upload(String snapshot) {
        if (!Files.isRegularFile(REMOTE_CONFIG)) {
            syncStatus = "ไม่ได้เชื่อมต่อเว็บ (มี Cache ในเครื่อง)";
            return;
        }
        try {
            JsonObject config = JsonParser.parseString(
                Files.readString(REMOTE_CONFIG, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!config.has("url") || !config.has("token")) {
                syncStatus = "ไฟล์เชื่อมต่อเว็บไม่ครบ"; return;
            }
            String url = config.get("url").getAsString();
            String token = config.get("token").getAsString();
            URI endpoint = URI.create(url);
            if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || !endpoint.getPath().endsWith("/api/sync")
                || token.length() < 32) {
                syncStatus = "ตั้งค่า URL หรือ Token ไม่ถูกต้อง"; return;
            }
            if (snapshot.length() > 900_000) {
                syncStatus = "ข้อมูลมากเกินขนาดอัปโหลด"; return;
            }
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"items\":" + snapshot + "}", StandardCharsets.UTF_8)).build();
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) {
                lastSent = snapshot;
                syncStatus = "ส่งข้อมูลขึ้นเว็บไซต์สำเร็จ";
            } else {
                syncStatus = "ส่งข้อมูลไม่สำเร็จ (HTTP " + response.statusCode() + ")";
                System.err.println("[Easylife] Remote price sync returned HTTP " + response.statusCode());
            }
        } catch (Exception ex) {
            syncStatus = "ไม่สามารถเชื่อมต่อเว็บไซต์";
            System.err.println("[Easylife] Remote price sync failed: " + ex.getClass().getSimpleName());
        }
    }
}
