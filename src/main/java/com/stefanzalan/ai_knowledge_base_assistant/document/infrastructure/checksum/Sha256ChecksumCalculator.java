package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.checksum;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes SHA-256 checksums used for document version change detection
 * and content-addressed file storage.
 */
@Component
public class Sha256ChecksumCalculator {

    public String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
