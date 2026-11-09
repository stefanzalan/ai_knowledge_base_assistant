package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownChunkerTest {

    @Test
    void splitsAtHeadingAndParagraphBoundaries() {
        MarkdownChunker chunker = new MarkdownChunker(120, 20);
        String markdown = """
                # Home Loans

                First paragraph about home loans.

                Second paragraph with more details.

                ## Interest Rates

                Rates paragraph here.
                """;

        List<String> chunks = chunker.chunk(markdown);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c).isNotBlank());
        // Order is preserved: home loan content appears before rates content.
        String joined = String.join("\n", chunks);
        assertThat(joined.indexOf("First paragraph")).isLessThan(joined.indexOf("Rates paragraph"));
    }

    @Test
    void preservesHeadingContextInChunks() {
        MarkdownChunker chunker = new MarkdownChunker(80, 10);
        String markdown = """
                # Fees

                Paragraph one with enough text to force a second chunk boundary here.

                Paragraph two also long enough to need its own chunk for sure.
                """;

        List<String> chunks = chunker.chunk(markdown);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c).contains("# Fees"));
    }

    @Test
    void hardSplitsOversizedParagraphWithOverlap() {
        MarkdownChunker chunker = new MarkdownChunker(100, 20);
        String longWord = "word ".repeat(100).trim();

        List<String> chunks = chunker.chunk(longWord);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(100));
    }

    @Test
    void keepsFencedCodeBlockAtomic() {
        MarkdownChunker chunker = new MarkdownChunker(500, 50);
        String markdown = """
                # Config

                Some intro text.

                ```java
                public class Example {
                    // a fairly long line of code that stays inside the fence
                    // another line to make the block bigger
                }
                ```

                Trailing paragraph.
                """;

        List<String> chunks = chunker.chunk(markdown);

        assertThat(chunks).anySatisfy(c -> {
            assertThat(c).contains("```java");
            assertThat(c).contains("public class Example");
        });
    }

    @Test
    void returnsEmptyListForBlankInput() {
        MarkdownChunker chunker = new MarkdownChunker(100, 10);

        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n\n  ")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new MarkdownChunker(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarkdownChunker(100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarkdownChunker(100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignsSequentialZeroBasedOrderImplicitly() {
        MarkdownChunker chunker = new MarkdownChunker(60, 10);
        String markdown = """
                # A

                Alpha paragraph one.

                Alpha paragraph two.

                # B

                Beta paragraph one.

                Beta paragraph two.
                """;

        List<String> chunks = chunker.chunk(markdown);

        assertThat(chunks).isNotEmpty();
        String first = chunks.get(0);
        String last = chunks.get(chunks.size() - 1);
        assertThat(first).contains("Alpha");
        assertThat(last).contains("Beta");
    }
}
