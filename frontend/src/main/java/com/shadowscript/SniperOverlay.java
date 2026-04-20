package com.shadowscript;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SniperOverlay extends Application {

    // ── Status Enum ──────────────────────────────────────────────────────────
    public enum Status {
        IDLE, CAPTURING, PROCESSING, ERROR
    }

    // ── Drag / Resize state ───────────────────────────────────────────────────
    private static final double HANDLE_SIZE = 12.0;
    private static final double MIN_SIZE = 120.0;

    private double dragStartX, dragStartY;
    private double dragStageX, dragStageY;
    private double dragStageW, dragStageH;
    private ResizeDirection resizeDir = ResizeDirection.NONE;

    // ── Fields ────────────────────────────────────────────────────────────────
    private Stage primaryStage;
    private CaptureService captureService;
    private final List<String> textBuffer = Collections.synchronizedList(new ArrayList<>());

    private Label statusDot;
    private TextArea ocrTextArea;

    // ═════════════════════════════════════════════════════════════════════════
    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // Prevent JavaFX from exiting when hide() is called during capture
        Platform.setImplicitExit(false);

        try {
            buildOverlay(stage);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Overlay failed to start");
        }
    }

    // ── Build UI ──────────────────────────────────────────────────────────────
    private void buildOverlay(Stage stage) throws Exception {

        // ── Status dot (top-left) ──────────────────────────────────────────────
        statusDot = new Label("●");
        statusDot.setStyle("-fx-text-fill: #888888; -fx-font-size: 13px;");

        // ── Title ──────────────────────────────────────────────────────────────
        Label title = new Label("ShadowScript");
        title.setStyle("-fx-text-fill: #00F5FF; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 0 0 0 6;");

        // ── Spacer ─────────────────────────────────────────────────────────────
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ── Toggle Capture ─────────────────────────────────────────────────────
        Button toggleCaptureBtn = new Button("⏹ Stop");
        styleExportBtn(toggleCaptureBtn);
        toggleCaptureBtn.setOnAction(e -> {
            if (captureService != null) {
                if (toggleCaptureBtn.getText().equals("⏹ Stop")) {
                    captureService.stop();
                    toggleCaptureBtn.setText("▶ Start");
                } else {
                    captureService.start();
                    toggleCaptureBtn.setText("⏹ Stop");
                }
            }
        });

        // ── Export: DOCX ───────────────────────────────────────────────────────
        Button docxBtn = new Button("⬇ DOCX");
        styleExportBtn(docxBtn);
        docxBtn.setOnAction(e -> exportDocx());

        // ── Export: PDF ────────────────────────────────────────────────────────
        Button pdfBtn = new Button("⬇ PDF");
        styleExportBtn(pdfBtn);
        pdfBtn.setOnAction(e -> exportPdf());

        // ── Close button (top-right) ───────────────────────────────────────────
        Button closeBtn = new Button("✕");
        closeBtn.setStyle("-fx-text-fill: #888888; -fx-background-color: transparent; " +
                "-fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 2 6 2 6;");
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(closeBtn.getStyle()
                .replace("-fx-text-fill: #888888", "-fx-text-fill: #FF4C4C")));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(closeBtn.getStyle()
                .replace("-fx-text-fill: #FF4C4C", "-fx-text-fill: #888888")));
        closeBtn.setOnAction(e -> {
            if (captureService != null)
                captureService.stop();
            Platform.exit();
        });

        // ── Top HBox ───────────────────────────────────────────────────────────
        HBox topBar = new HBox(4, statusDot, title, spacer, toggleCaptureBtn, docxBtn, pdfBtn, closeBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-padding: 4 8 4 8; -fx-background-color: rgba(10,12,20,0.55);");

        // ── OCR Text Area ──────────────────────────────────────────────────────
        ocrTextArea = new TextArea();
        ocrTextArea.setEditable(false);
        ocrTextArea.setWrapText(true);
        ocrTextArea.setId("ocrArea");
        ocrTextArea.setStyle(
                "-fx-control-inner-background: transparent; " +
                        "-fx-background-color: transparent; " +
                        "-fx-text-fill: #e0e0e0; " +
                        "-fx-font-family: 'Consolas', monospace; " +
                        "-fx-font-size: 12px; " +
                        "-fx-highlight-fill: #00F5FF; " +
                        "-fx-highlight-text-fill: #000000;");

        // ── Root ───────────────────────────────────────────────────────────────
        VBox root = new VBox(topBar, ocrTextArea);
        VBox.setVgrow(ocrTextArea, Priority.ALWAYS);
        root.setStyle(
                "-fx-border-color: #00F5FF; " +
                        "-fx-border-width: 2px; " +
                        "-fx-border-style: solid; " +
                        "-fx-background-color: rgba(10,12,20,0.15);");

        // ── Neon glow ──────────────────────────────────────────────────────────
        DropShadow glow = new DropShadow();
        glow.setColor(Color.web("#00F5FF", 0.55));
        glow.setRadius(14);
        glow.setSpread(0.15);
        root.setEffect(glow);

        // ── Drag / resize ──────────────────────────────────────────────────────
        attachDragHandlers(root);

        // ── Scene ──────────────────────────────────────────────────────────────
        Scene scene = new Scene(root, 800, 80);
        scene.setFill(Color.TRANSPARENT);

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle("ShadowScript Sniper");
        stage.setScene(scene);

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setX((bounds.getWidth() - 800) / 2);
        stage.setY(bounds.getHeight() - 160); // near bottom — over caption bar
        stage.show();

        // JavaFX TextArea wraps an internal ScrollPane that ignores inline CSS.
        // We must look it up AFTER the scene graph is rendered and patch it
        // programmatically — this is the only reliable transparency fix.
        ocrTextArea.widthProperty().addListener((obs, oldVal, newVal) -> {
            javafx.scene.Node scrollPane = ocrTextArea.lookup(".scroll-pane");
            if (scrollPane != null) {
                scrollPane.setStyle(
                        "-fx-background: transparent; " +
                                "-fx-background-color: transparent;");
            }
            javafx.scene.Node viewport = ocrTextArea.lookup(".scroll-pane .viewport");
            if (viewport != null) {
                viewport.setStyle("-fx-background-color: transparent;");
            }
        });

        // ── CaptureService ─────────────────────────────────────────────────────
        safeInitCaptureService();
    }

    // ── Status dot ────────────────────────────────────────────────────────────
    public void setStatus(Status s) {
        Platform.runLater(() -> {
            String color = switch (s) {
                case IDLE -> "#888888";
                case CAPTURING -> "#00F5FF";
                case PROCESSING -> "#FFD700";
                case ERROR -> "#FF4C4C";
            };
            statusDot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 13px;");
        });
    }

    // ── Export helpers ────────────────────────────────────────────────────────
    private void styleExportBtn(Button btn) {
        btn.setStyle(
                "-fx-text-fill: #888888; -fx-background-color: transparent; " +
                        "-fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 2 6 2 6;");
        btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle()
                .replace("-fx-text-fill: #888888", "-fx-text-fill: #00F5FF")));
        btn.setOnMouseExited(e -> btn.setStyle(btn.getStyle()
                .replace("-fx-text-fill: #00F5FF", "-fx-text-fill: #888888")));
    }

    private void exportDocx() {
        File file = chooseFile("Save as DOCX", "Word Document (*.docx)", "*.docx");
        if (file == null)
            return;
        try (XWPFDocument doc = new XWPFDocument();
                FileOutputStream out = new FileOutputStream(file)) {
            String[] lines = ocrTextArea.getText().split("\n");
            for (String line : lines) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setText(line);
            }
            doc.write(out);
            showSaveAlert(file);
        } catch (Exception ex) {
            ex.printStackTrace();
            showErrorAlert("DOCX export failed: " + ex.getMessage());
        }
    }

    private void exportPdf() {
        File file = chooseFile("Save as PDF", "PDF Document (*.pdf)", "*.pdf");
        if (file == null)
            return;
        try (PdfWriter writer = new PdfWriter(file);
                PdfDocument pdf = new PdfDocument(writer);
                Document doc = new Document(pdf)) {
            String[] lines = ocrTextArea.getText().split("\n");
            for (String line : lines) {
                doc.add(new Paragraph(line));
            }
            showSaveAlert(file);
        } catch (Exception ex) {
            ex.printStackTrace();
            showErrorAlert("PDF export failed: " + ex.getMessage());
        }
    }

    private File chooseFile(String title, String desc, String ext) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(desc, ext));
        return fc.showSaveDialog(primaryStage);
    }

    private void showSaveAlert(File file) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Saved!");
            alert.setHeaderText(null);
            alert.setContentText(
                    "✅ Saved! You can now paste this file into ChatGPT, Claude, or Gemini manually.\n\nPath: "
                            + file.getAbsolutePath());
            alert.showAndWait();
        });
    }

    private void showErrorAlert(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Export Error");
            alert.setHeaderText(null);
            alert.setContentText(msg);
            alert.showAndWait();
        });
    }

    // ── CaptureService init ───────────────────────────────────────────────────
    private void safeInitCaptureService() {
        try {
            captureService = new CaptureService(
                    this::getCaptureRegion,
                    text -> {
                        textBuffer.add(text);
                        Platform.runLater(() -> {
                            ocrTextArea.appendText(text + "\n");
                            ocrTextArea.setScrollTop(Double.MAX_VALUE);
                        });
                    },
                    error -> {
                        setStatus(Status.ERROR);
                        Platform.runLater(() -> ocrTextArea.appendText("[ERROR] " + error.getMessage() + "\n"));
                    },
                    this::setStatus,
                    primaryStage // ← for hide-during-capture
            );
            captureService.start();
        } catch (Throwable e) {
            e.printStackTrace();
            System.err.println("⚠ CaptureService failed but app continues");
        }
    }

    // ── FrameRegion ───────────────────────────────────────────────────────────
    public CaptureService.FrameRegion getCaptureRegion() {
        // Capture FULL screen — bridge.py crops to caption area.
        // Never use the overlay's own bounds (it would photograph itself).
        Rectangle2D b = Screen.getPrimary().getVisualBounds();
        return new CaptureService.FrameRegion(
                (int) b.getMinX(), (int) b.getMinY(),
                (int) b.getWidth(), (int) b.getHeight());
    }

    // ── Drag / resize handlers ────────────────────────────────────────────────
    private void attachDragHandlers(VBox root) {
        root.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            resizeDir = getResizeDir(e.getX(), e.getY(), root.getWidth(), root.getHeight());
            root.setCursor(resizeDir.cursor);
        });

        root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            dragStartX = e.getScreenX();
            dragStartY = e.getScreenY();
            dragStageX = primaryStage.getX();
            dragStageY = primaryStage.getY();
            dragStageW = primaryStage.getWidth();
            dragStageH = primaryStage.getHeight();
            resizeDir = getResizeDir(e.getX(), e.getY(), root.getWidth(), root.getHeight());
        });

        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            double dx = e.getScreenX() - dragStartX;
            double dy = e.getScreenY() - dragStartY;
            if (resizeDir == ResizeDirection.NONE) {
                primaryStage.setX(dragStageX + dx);
                primaryStage.setY(dragStageY + dy);
            } else {
                applyResize(dx, dy);
            }
        });
    }

    private ResizeDirection getResizeDir(double x, double y, double w, double h) {
        boolean L = x < HANDLE_SIZE, R = x > w - HANDLE_SIZE;
        boolean T = y < HANDLE_SIZE, B = y > h - HANDLE_SIZE;
        if (!L && !R && !T && !B)
            return ResizeDirection.NONE;
        if (T && L)
            return ResizeDirection.NW;
        if (T && R)
            return ResizeDirection.NE;
        if (B && L)
            return ResizeDirection.SW;
        if (B && R)
            return ResizeDirection.SE;
        if (T)
            return ResizeDirection.N;
        if (B)
            return ResizeDirection.S;
        if (L)
            return ResizeDirection.W;
        return ResizeDirection.E;
    }

    private void applyResize(double dx, double dy) {
        double nX = dragStageX, nY = dragStageY, nW = dragStageW, nH = dragStageH;
        switch (resizeDir) {
            case E -> nW = Math.max(MIN_SIZE, dragStageW + dx);
            case S -> nH = Math.max(MIN_SIZE, dragStageH + dy);
            case W -> {
                nX = dragStageX + dx;
                nW = Math.max(MIN_SIZE, dragStageW - dx);
            }
            case N -> {
                nY = dragStageY + dy;
                nH = Math.max(MIN_SIZE, dragStageH - dy);
            }
            case SE -> {
                nW = Math.max(MIN_SIZE, dragStageW + dx);
                nH = Math.max(MIN_SIZE, dragStageH + dy);
            }
            case SW -> {
                nX = dragStageX + dx;
                nW = Math.max(MIN_SIZE, dragStageW - dx);
                nH = Math.max(MIN_SIZE, dragStageH + dy);
            }
            case NE -> {
                nW = Math.max(MIN_SIZE, dragStageW + dx);
                nY = dragStageY + dy;
                nH = Math.max(MIN_SIZE, dragStageH - dy);
            }
            case NW -> {
                nX = dragStageX + dx;
                nW = Math.max(MIN_SIZE, dragStageW - dx);
                nY = dragStageY + dy;
                nH = Math.max(MIN_SIZE, dragStageH - dy);
            }
            default -> {
            }
        }
        primaryStage.setX(nX);
        primaryStage.setY(nY);
        primaryStage.setWidth(nW);
        primaryStage.setHeight(nH);
    }

    // ── ResizeDirection ───────────────────────────────────────────────────────
    private enum ResizeDirection {
        NONE(javafx.scene.Cursor.DEFAULT),
        N(javafx.scene.Cursor.N_RESIZE), S(javafx.scene.Cursor.S_RESIZE),
        E(javafx.scene.Cursor.E_RESIZE), W(javafx.scene.Cursor.W_RESIZE),
        NE(javafx.scene.Cursor.NE_RESIZE), NW(javafx.scene.Cursor.NW_RESIZE),
        SE(javafx.scene.Cursor.SE_RESIZE), SW(javafx.scene.Cursor.SW_RESIZE);

        final javafx.scene.Cursor cursor;

        ResizeDirection(javafx.scene.Cursor c) {
            this.cursor = c;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}