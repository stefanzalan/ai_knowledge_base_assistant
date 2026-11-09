package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.checksum;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256ChecksumCalculatorTest {

    private final Sha256ChecksumCalculator calculator = new Sha256ChecksumCalculator();

    @Test
    void producesKnownSha256Digest() {
        // Well-known SHA-256 test vector for "abc".
        String hex = calculator.sha256Hex("abc".getBytes(StandardCharsets.UTF_8));

        assertThat(hex).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sameContentProducesSameChecksum() {
        byte[] content = "# Title\n\nSome content".getBytes(StandardCharsets.UTF_8);

        assertThat(calculator.sha256Hex(content)).isEqualTo(calculator.sha256Hex(content));
    }

    @Test
    void differentContentProducesDifferentChecksum() {
        assertThat(calculator.sha256Hex("a".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(calculator.sha256Hex("b".getBytes(StandardCharsets.UTF_8)));
    }
}
