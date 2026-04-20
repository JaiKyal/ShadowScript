package com.shadowscript;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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

    /** Handle to the bridge.exe subprocess (null if not launched by us). */
    private Process bridgeProcess = null;

    // ═════════════════════════════════════════════════════════════════════════
    public CaptureService(
            RegionSupplier regionSupplier,
            Consumer<String> onText,
            Consumer<Throwable> onError,
            Consumer<SniperOverlay.Status> onStatus,
            Stage primaryStage) {
        this.regionSupplier = regionSupplier;
        this.onText          = onText;
        this.onError         = onError;
        this.onStatus        = onStatus;
        this.primaryStage    = primaryStage;

        this.jsonMapper = new ObjectMapper();
        // Force HTTP/1.1 — prevents HTTP/2 upgrade header that uvicorn
        // logs as "Invalid HTTP request received".
        // connectTimeout covers TCP handshake only; per-request timeout is set
        // separately in sendAsyncAttempt().
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try {
            this.robot = new Robot();
        } catch (Exception e) {
            System.err.println("[CaptureService] Robot init failed: " + e.getMessage());
            this.robot = null;
        }

        // ── Auto-launch bridge.exe if it lives next to the jar ────────────────
        launchBridgeIfPresent();
    }

    // ── bridge.exe subprocess ─────────────────────────────────────────────────

    /**
     * Looks for bridge.exe in the same directory as the running jar.
     * If found, launches it as a background process and registers a JVM
     * shutdown hook to destroy it cleanly when the app exits.
     */
    private void launchBridgeIfPresent() {
        try {
            // Resolve the directory that contains the running jar
            File jarDir = new File(
                CaptureService.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
            ).getParentFile();

            File bridgeExe = new File(jarDir, "bridge.exe");

            if (!bridgeExe.exists()) {
                System.out.println("[CaptureService] bridge.exe not found next to jar — skipping auto-launch.");
                return;
            }

            System.out.println("[CaptureService] Launching bridge.exe from: " + bridgeExe.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(bridgeExe.getAbsolutePath());
            pb.directory(jarDir);
            // Redirect output to the JVM's own stdout/stderr so logs are visible
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            bridgeProcess = pb.start();
            System.out.println("[CaptureService] bridge.exe started (PID "
                    + bridgeProcess.pid() + ").");

            // Register shutdown hook — kills bridge.exe when the JVM exits
            Process proc = bridgeProcess;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (proc.isAlive()) {
                    System.out.println("[CaptureService] Shutdown hook: terminating bridge.exe…");
                    proc.destroy();
                    try {
                        // Give it 3 s to exit gracefully, then force-kill
                        if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                            proc.destroyForcibly();
                        }
                    } catch (InterruptedException ie) {
                        proc.destroyForcibly();
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("[CaptureService] bridge.exe terminated.");
                }
            }, "bridge-shutdown-hook"));

        } catch (Exception e) {
            System.err.println("[CaptureService] Failed to launch bridge.exe: " + e.getMessage());
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
            String base64   = Base64.getEncoder().encodeToString(baos.toByteArray());
            String jsonBody  = "{\"image\":\"" + base64 + "\"}";

            onStatus.accept(SniperOverlay.Status.PROCESSING);
            sendAsync(jsonBody);

        } catch (InterruptedException e) {
            // Scheduler is shutting down — restore overlay and bail cleanly
            Platform.runLater(() -> primaryStage.setOpacity(1));
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Platform.runLater(() -> primaryStage.setOpacity(1)); // always restore
            onStatus.accept(SniperOverlay.Status.ERROR);
            onError.accept(e);
        }
    }

    // ── HTTP POST to OCR backend ──────────────────────────────────────────────

    /** Public entry-point — always starts as the first (non-retry) attempt. */
    private void sendAsync(String jsonBody) {
        sendAsyncAttempt(jsonBody, false);
    }

    /**
     * Executes an async POST to the OCR backend.
     *
     * @param jsonBody  JSON payload to send
     * @param isRetry   {@code true} on the single automatic retry after a
     *                  timeout; prevents infinite retry loops
     */
    private void sendAsyncAttempt(String jsonBody, boolean isRetry) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/process-frame"))
                    .header("Content-Type", "application/json")
                    // OCR can legitimately take 15-20 s on slow hardware
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        try {
                            if (res.statusCode() == 200) {
                                String text = jsonMapper
                                        .readTree(res.body())
                                        .get("text")
                                        .asText();

                                // Guard: skip blank / duplicate frames — keeps
                                // the TextArea free of empty lines
                                if (text != null && !text.isBlank()) {
                                    onText.accept(text);
                                }
                                onStatus.accept(SniperOverlay.Status.IDLE);
                            } else {
                                System.err.println("[CaptureService] HTTP "
                                        + res.statusCode()
                                        + " | body: " + res.body());
                                onStatus.accept(SniperOverlay.Status.ERROR);
                                onError.accept(new RuntimeException(
                                        "Backend returned HTTP " + res.statusCode()
                                        + ": " + res.body()));
                            }
                        } catch (Exception e) {
                            System.err.println("[CaptureService] parse error: "
                                    + e.getMessage());
                            onStatus.accept(SniperOverlay.Status.ERROR);
                            onError.accept(e);
                        }
                    })
                    .exceptionally(ex -> {
                        // CompletableFuture wraps in CompletionException; unwrap it
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

                        if (cause instanceof HttpTimeoutException && !isRetry) {
                            // ── Single automatic retry after 2 s ──────────────
                            System.err.println(
                                    "[CaptureService] Request timed out — retrying in 2 s…");
                            try {
                                Thread.sleep(2_000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return null; // scheduler shutting down; bail cleanly
                            }
                            sendAsyncAttempt(jsonBody, true);
                        } else {
                            // Retry already exhausted, or a non-timeout failure
                            System.err.println("[CaptureService] Request failed"
                                    + (isRetry ? " (after retry)" : "")
                                    + ": " + cause.getMessage());
                            onStatus.accept(SniperOverlay.Status.ERROR);
                            onError.accept(cause);
                        }
                        return null;
                    });

        } catch (Exception e) {
            System.err.println("[CaptureService] sendAsync setup error: "
                    + e.getMessage());
            onStatus.accept(SniperOverlay.Status.ERROR);
            onError.accept(e);
        }
    }
}