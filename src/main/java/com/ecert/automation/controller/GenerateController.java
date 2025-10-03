package com.ecert.automation.controller;

import com.ecert.automation.service.CertificateService;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.mail.internet.MimeMessage;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
public class GenerateController {

    @Autowired
    private CertificateService certService;

    @Autowired(required = false)
    private JavaMailSender mailSender; // optional

    @PostMapping("/generate")
    public ResponseEntity<InputStreamResource> generate(
            @RequestParam("csv") MultipartFile csv,
            @RequestParam(value = "template", required = false) MultipartFile template,
            @RequestParam(value = "sendEmail", defaultValue = "false") boolean sendEmail
    ) throws Exception {

        File tmp = Files.createTempDirectory("ecert-cert-").toFile();
        tmp.deleteOnExit();

        List<Map<String, String>> rows = certService.parseCsv(csv.getInputStream());

        List<File> generated = new ArrayList<>();
        for (Map<String, String> row : rows) {
            try {
                File pdf = certService.generateCertificate(row, template, tmp);
                generated.add(pdf);
                if (sendEmail && mailSender != null) {
                    sendEmailWithAttachment(row.get("email"), "Your Certificate", "Please find attached", pdf);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        File zip = new File(tmp, "certificates.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            for (File f : generated) {
                ZipEntry entry = new ZipEntry(f.getName());
                zos.putNextEntry(entry);
                Files.copy(f.toPath(), zos);
                zos.closeEntry();
            }
        }

        InputStreamResource resource = new InputStreamResource(new FileInputStream(zip));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename("certificates.zip").build());
        headers.setContentLength(zip.length());
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .headers(headers)
                .body(resource);
    }

    private void sendEmailWithAttachment(String to, String subj, String body, File attachment) {
        if (to == null || to.isBlank() || mailSender == null) return;
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subj);
            helper.setText(body, true);
            helper.addAttachment(attachment.getName(), attachment);
            mailSender.send(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @GetMapping("/verify")
    public ResponseEntity<String> verify(@RequestParam("reg") String reg) {
        String html = "<html><body><h2>Certificate verification</h2>" +
                "<p>Registration: " + reg + "</p>" +
                "<p>This is a demo verification page.</p>" +
                "</body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }
}
