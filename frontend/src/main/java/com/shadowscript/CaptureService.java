package com.shadowscript;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.stage.Stage;

public class CaptureService {

    public record FrameRegion(int x, int y, int width, int height) {
    }

    // Serialises to exactly {"image":"<base64>"} — matches Pydantic FramePayload
    public record FramePayload(String image) {
    }

    @FunctionalInterface
    public interface RegionSupplier {
        FrameRegion get();
    }

    private final RegionSupplier regionSupplier;
    private final Consumer<String> onText;
    private final Consumer<Throwable> onError;
    private final Consumer<SniperOverlay.Status> onStatus;
    private final Stage primaryStage;

    private final HttpClient httpClient;
    private final ObjectMapper jsonMapper;

    private Robot robot;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public CaptureService(
            RegionSupplier regionSupplier,
            Consumer<String> onText,
            Consumer<Throwable> onError,
            Consumer<SniperOverlay.Status> onStatus,
            Stage primaryStage) {
        this.regionSupplier = regionSupplier;
        this.onText = onText;
        this.onError = onError;
        this.onStatus = onStatus;
        this.primaryStage = primaryStage;

        this.jsonMapper = new ObjectMapper();
        // Force HTTP/1.1 + timeout — prevents HTTP/2 upgrade header that
        // uvicorn logs as "Invalid HTTP request received"
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        try {
            this.robot = new Robot();
        } catch (Exception e) {
            System.err.println("Robot init failed: " + e.getMessage());
            this.robot = null;
        }
    }

    // ── Start scheduled captures ──────────────────────────────────────────────
    public synchronized void start() {
        if (running || robot == null)
            return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "capture-thread");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::captureAndSend, 0, 1500, TimeUnit.MILLISECONDS);
        onStatus.accept(SniperOverlay.Status.IDLE);
    }

    // ── Stop ──────────────────────────────────────────────────────────────────
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        onStatus.accept(SniperOverlay.Status.IDLE);
    }

    // ── Capture loop ──────────────────────────────────────────────────────────
    private void captureAndSend() {
        if (!running)
            return;
        try {
            onStatus.accept(SniperOverlay.Status.CAPTURING);

            FrameRegion region = regionSupplier.get();

            // Make overlay invisible (opacity=0 is smooth, no flash)
            CompletableFuture<Void> hidden = new CompletableFuture<>();
            Platform.runLater(() -> {
                primaryStage.setOpacity(0);
                hidden.complete(null);
            });
            hidden.get(1, TimeUnit.SECONDS); // wait for JavaFX to apply
            Thread.sleep(150); // let compositor render the change

            BufferedImage img = robot.createScreenCapture(
                    new Rectangle(region.x(), region.y(), region.width(), region.height()));

            // Restore overlay
            Platform.runLater(() -> primaryStage.setOpacity(1));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            String jsonBody = "{\"image\":\"" + base64 + "\"}";

            onStatus.accept(SniperOverlay.Status.PROCESSING);
            sendAsync(jsonBody);

        } catch (Exception e) {
            Platform.runLater(() -> primaryStage.setOpacity(1)); // always restore
            onStatus.accept(SniperOverlay.Status.ERROR);
            onError.accept(e);
        }
    }

    // ── HTTP POST to Tesseract backend ────────────────────────────────────────
    private void sendAsync(String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/process-frame"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        try {
                            if (res.statusCode() == 200) {
                                String text = jsonMapper.readTree(res.body()).get("text").asText();
                                onText.accept(text);
                                onStatus.accept(SniperOverlay.Status.IDLE);
                            } else {
                                onStatus.accept(SniperOverlay.Status.ERROR);
                                onError.accept(new RuntimeException("HTTP " + res.statusCode()));
                            }
                        } catch (Exception e) {
                            onStatus.accept(SniperOverlay.Status.ERROR);
                            onError.accept(e);
                        }
                    });

        } catch (Exception e) {
            onStatus.accept(SniperOverlay.Status.ERROR);
            onError.accept(e);
        }
    }
}