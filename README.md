# ShadowScript

ShadowScript is a desktop application for capturing on-screen text in real time using OCR.

The application provides a resizable capture interface that can be positioned over text displayed on the screen. Captured frames are processed through a local Python OCR backend, and the extracted text is returned to the JavaFX application.

## Features

- Resizable on-screen capture area
- Real-time text extraction using OCR
- Tesseract OCR processing
- OpenCV-based image preprocessing
- Duplicate text detection
- OCR text cleanup and spell correction
- Export extracted text to DOCX and PDF
- JavaFX desktop interface
- Local FastAPI backend for OCR processing

## How It Works

```text
Screen Content
      ↓
Resizable Capture Area
      ↓
JavaFX Frontend
      ↓
FastAPI OCR Bridge
      ↓
OpenCV Preprocessing
      ↓
Tesseract OCR
      ↓
Text Cleanup & Spell Correction
      ↓
Extracted Text
      ↓
DOCX / PDF Export
