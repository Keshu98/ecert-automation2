package com.ecert.automation.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import org.apache.commons.io.IOUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

@Service
public class CertificateService {

    public File generateCertificate(Map<String, String> row, MultipartFile templateFile, File tempDir) throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);

        // Load template image (uploaded or fallback to classpath)
        PDImageXObject bgImage = loadTemplateImage(doc, templateFile);

        // Load Unicode font from classpath
        PDType0Font font;
        try (InputStream fis = new ClassPathResource("fonts/NotoSans-Regular.ttf").getInputStream()) {
            font = PDType0Font.load(doc, fis);
        }

        PDPageContentStream cs = new PDPageContentStream(doc, page);

        // Draw full background (stretched)
        float pw = page.getMediaBox().getWidth();
        float ph = page.getMediaBox().getHeight();
        cs.drawImage(bgImage, 0, 0, pw, ph);

        // Participant name - center
        String name = row.getOrDefault("name", "Participant").trim();
        if (name.isEmpty()) name = "Participant";

        float maxTextWidth = pw - 140; // left+right margins (adjust)
        float fontSize = fitFontSizeToWidth(font, name, maxTextWidth, 40f);
        float textWidth = font.getStringWidth(name) / 1000 * fontSize;
        float x = (pw - textWidth) / 2;
        float y = ph - 300; // adjust vertical position according to your template

        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, y);
        cs.showText(name);
        cs.endText();

        // Add extra (award) text
        String extra = row.getOrDefault("award", "");
        if (!extra.isBlank()) {
            float esize = 14f;
            float exWidth = font.getStringWidth(extra) / 1000 * esize;
            float exX = (pw - exWidth) / 2;
            cs.beginText();
            cs.setFont(font, esize);
            cs.newLineAtOffset(exX, y - 36);
            cs.showText(extra);
            cs.endText();
        }

        // QR code (bottom-right)
        String regId = row.getOrDefault("registration_id", UUID.randomUUID().toString());
        String qrData = "http://localhost:8080/api/verify?reg=" + URLEncoder.encode(regId, StandardCharsets.UTF_8);
        BufferedImage qr = generateQRCodeImage(qrData, 220);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(qr, "PNG", baos);
        PDImageXObject qrImage = PDImageXObject.createFromByteArray(doc, baos.toByteArray(), "qr");
        cs.drawImage(qrImage, pw - 160, 60, 120, 120); // adjust position to match your template

        cs.close();

        String filename = sanitizeFileName(name) + "-" + regId + ".pdf";
        File out = new File(tempDir, filename);
        doc.save(out);
        doc.close();
        return out;
    }

    private PDImageXObject loadTemplateImage(PDDocument doc, MultipartFile templateFile) throws IOException {
        byte[] bytes;
        if (templateFile != null && !templateFile.isEmpty()) {
            try (InputStream is = templateFile.getInputStream()) {
                bytes = IOUtils.toByteArray(is);
            }
        } else {
            ClassPathResource res = new ClassPathResource("static/template.png");
            try (InputStream is = res.getInputStream()) {
                bytes = IOUtils.toByteArray(is);
            }
        }
        return PDImageXObject.createFromByteArray(doc, bytes, "template");
    }

    private float fitFontSizeToWidth(PDType0Font font, String text, float maxWidth, float initialSize) throws IOException {
        float size = initialSize;
        float textWidth = font.getStringWidth(text) / 1000 * size;
        while (textWidth > maxWidth && size > 8f) {
            size -= 1f;
            textWidth = font.getStringWidth(text) / 1000 * size;
        }
        return size;
    }

    private BufferedImage generateQRCodeImage(String data, int size) throws WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints);
        return MatrixToImageWriter.toBufferedImage(matrix);
    }

    private String sanitizeFileName(String s) {
        return s.replaceAll("[^a-zA-Z0-9\\-_. ]", "").trim();
    }

    public List<Map<String, String>> parseCsv(InputStream csvIs) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        Reader in = new InputStreamReader(csvIs, StandardCharsets.UTF_8);
        Iterable<CSVRecord> records = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(in);
        for (CSVRecord r : records) {
            Map<String, String> m = new HashMap<>();
            r.toMap().forEach((k, v) -> m.put(k.trim(), v == null ? "" : v.trim()));
            rows.add(m);
        }
        return rows;
    }
}
