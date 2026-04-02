package dev.thanh.spring_ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * Smart parser — routes each uploaded file to the optimal reader.
 * <ul>
 * <li>PDF → {@link PagePdfDocumentReader} (1 Document per page, preserves
 * structure)</li>
 * <li>DOCX / DOC / XLSX / TXT / HTML / PPT … → {@link TikaDocumentReader}
 * (Apache Tika)</li>
 * </ul>
 */
@Service
@Slf4j(topic = "DOCUMENT-PARSER")
public class DocumentParserService {

    /** File extension that gets dedicated PDF reader for page-level splitting. */
    private static final String PDF_EXT = "pdf";

    /** Whitelist — only text/document formats accepted. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            PDF_EXT,
            "doc", "docx", // Word
            "txt", "csv", "rtf", // Plain text
            "html", "htm", // Web
            "odt" // OpenDocument Text
    );

    /**
     * Parse the uploaded file into a list of Spring AI {@link Document} objects.
     *
     * @param file uploaded multipart file
     * @return parsed documents (1 per page for PDF, 1-N for others)
     * @throws IllegalArgumentException if the file is empty or has an unsupported
     *                                  extension
     * @throws DocumentParseException   if the underlying reader fails (corrupt /
     *                                  password-protected…)
     */
    public List<Document> parse(MultipartFile file, String filename) {
        String extension = extractExtension(filename);

        validateSupportedType(extension, filename);

        return doParse(file.getResource(), filename, extension);
    }

    // ─── Core parsing ─────────────────────────────────────────────────────────

    private List<Document> doParse(Resource resource, String filename, String extension) {
        long start = System.nanoTime();
        try {
            List<Document> docs = PDF_EXT.equals(extension)
                    ? parsePdf(resource, filename)
                    : parseTika(resource, filename);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[PARSER] Parsed '{}' in {}ms — {} document(s)", filename, elapsedMs, docs.size());
            return docs;

        } catch (Exception e) {
            log.error("[PARSER] Failed to parse '{}': {}", filename, e.getMessage());
            throw new RuntimeException("Failed to parse file '" + filename + "': " + e.getMessage(), e);
        }
    }

    /**
     * PDF → PagePdfDocumentReader (1 Document per page).
     */
    private List<Document> parsePdf(Resource resource, String filename) {
        log.info("[PARSER] PDF detected — using PagePdfDocumentReader for '{}'", filename);
        return new PagePdfDocumentReader(resource).read();
    }

    /**
     * Everything else → TikaDocumentReader.
     * <p>
     * <b>Word files (.doc, .docx) are parsed here.</b>
     * Tika auto-detects MIME type and delegates to the correct parser
     * (Apache POI for Office formats, etc.).
     * </p>
     */
    private List<Document> parseTika(Resource resource, String filename) {
        log.info("[PARSER] Non-PDF detected — using TikaDocumentReader for '{}'", filename);
        return new TikaDocumentReader(resource).read();
    }

    private void validateSupportedType(String extension, String filename) {
        if (extension.isEmpty()) {
            log.warn("[PARSER] File '{}' has no extension — falling back to Tika auto-detect", filename);
            return; // let Tika try MIME-type detection
        }
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported file type '." + extension + "' for file '" + filename
                            + "'. Supported: " + SUPPORTED_EXTENSIONS);
        }
    }

    // ─── Filename helpers ─────────────────────────────────────────────────────

    private String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }
}
