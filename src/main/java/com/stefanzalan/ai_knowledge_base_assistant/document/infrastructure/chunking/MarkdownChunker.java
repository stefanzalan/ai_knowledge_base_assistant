package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.chunking;

import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.config.IngestionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits Markdown documents into retrieval-sized chunks.
 *
 * <p>Strategy:</p>
 * <ul>
 *   <li>The document is parsed into blocks: ATX headings, paragraphs
 *       (blank-line separated) and fenced code blocks (kept atomic).</li>
 *   <li>Blocks are packed into chunks up to {@code chunkSize} characters,
 *       preferring heading/paragraph boundaries over mid-paragraph splits.</li>
 *   <li>Each chunk carries the nearest preceding heading as context when the
 *       chunk does not already contain it, so chunks remain self-contained
 *       for retrieval and citation.</li>
 *   <li>A single block larger than {@code chunkSize} (e.g. a huge paragraph
 *       or code fence) is hard-split at whitespace boundaries with
 *       {@code chunkOverlap} characters of carry-over between pieces.</li>
 *   <li>Between consecutive chunks, up to {@code chunkOverlap} characters of
 *       the previous chunk tail are prepended for continuity.</li>
 * </ul>
 *
 * <p>Limitations: chunk sizes are measured in characters, not tokens; setext
 * headings (underlined with {@code ===}/{@code ---}) are treated as paragraph
 * text; HTML blocks are not specially handled.</p>
 */
@Component
public class MarkdownChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    @Autowired
    public MarkdownChunker(IngestionProperties properties) {
        this(properties.getChunkSize(), properties.getChunkOverlap());
    }

    public MarkdownChunker(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be >= 0 and < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * Splits the Markdown content into ordered, non-empty chunks.
     * Returns an empty list for blank input.
     */
    public List<String> chunk(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        List<Block> blocks = parseBlocks(markdown);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentHeading = null;

        for (Block block : blocks) {
            if (block.heading()) {
                currentHeading = block.text();
            }

            int reserve = headingReserve(block, currentHeading);
            List<String> pieces = splitOversized(block.text(), Math.max(chunkSize - reserve, 100));

            for (String piece : pieces) {
                String candidate = withHeadingContext(piece, block, currentHeading, current);

                if (current.length() > 0 && current.length() + 2 + candidate.length() > chunkSize) {
                    flush(current, chunks);
                    current.setLength(0);
                    String tail = overlapTail(chunks.get(chunks.size() - 1));
                    // The overlap tail must not push the new chunk past
                    // chunkSize: keep only as much of it as fits alongside
                    // the candidate piece.
                    int maxTailLength = Math.max(chunkSize - candidate.length() - 2, 0);
                    if (tail.length() > maxTailLength) {
                        tail = tail.substring(tail.length() - maxTailLength).trim();
                    }
                    if (!tail.isEmpty()) {
                        current.append(tail).append("\n\n");
                    }
                    // Re-evaluate heading context: the overlap tail may already
                    // contain the heading, otherwise it is re-applied below.
                    candidate = withHeadingContext(piece, block, currentHeading, current);
                }

                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(candidate);
            }
        }

        flush(current, chunks);
        chunks.removeIf(String::isBlank);
        return chunks;
    }

    private int headingReserve(Block block, String currentHeading) {
        if (block.heading() || currentHeading == null) {
            return 0;
        }
        return currentHeading.length() + 2;
    }

    private String withHeadingContext(String piece, Block block, String currentHeading, StringBuilder current) {
        if (block.heading() || currentHeading == null) {
            return piece;
        }
        boolean headingPresent = current.length() > 0 && current.toString().contains(currentHeading);
        return headingPresent ? piece : currentHeading + "\n\n" + piece;
    }

    private void flush(StringBuilder current, List<String> chunks) {
        if (current.length() > 0 && !current.toString().isBlank()) {
            chunks.add(current.toString().trim());
        }
    }

    /**
     * Returns the tail of the previous chunk to prepend to the next one,
     * cut at a whitespace boundary to avoid splitting words.
     */
    private String overlapTail(String previousChunk) {
        if (chunkOverlap <= 0 || previousChunk == null || previousChunk.isBlank()) {
            return "";
        }
        String tail = previousChunk.length() <= chunkOverlap
                ? previousChunk
                : previousChunk.substring(previousChunk.length() - chunkOverlap);
        int firstWhitespace = -1;
        for (int i = 0; i < tail.length(); i++) {
            if (Character.isWhitespace(tail.charAt(i))) {
                firstWhitespace = i;
                break;
            }
        }
        if (firstWhitespace < 0) {
            return "";
        }
        return tail.substring(firstWhitespace).trim();
    }

    /**
     * Splits a single block that exceeds the capacity into pieces of at
     * most {@code capacity} characters, preferring whitespace boundaries
     * and applying {@code chunkOverlap} carry-over between pieces.
     */
    private List<String> splitOversized(String text, int capacity) {
        if (text.length() <= capacity) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        int pos = 0;
        int length = text.length();
        while (pos < length) {
            int end = Math.min(pos + capacity, length);
            if (end < length) {
                int breakAt = lastWhitespaceBefore(text, pos, end);
                if (breakAt > pos) {
                    end = breakAt;
                }
            }
            String piece = text.substring(pos, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= length) {
                break;
            }
            pos = Math.max(end - chunkOverlap, pos + 1);
            while (pos < length && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
        return pieces;
    }

    private int lastWhitespaceBefore(String text, int from, int to) {
        for (int i = to - 1; i > from; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private List<Block> parseBlocks(String markdown) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        StringBuilder fence = null;
        String fenceMarker = null;

        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.strip();

            if (fence != null) {
                fence.append('\n').append(line);
                if (isFenceEnd(trimmed, fenceMarker)) {
                    blocks.add(new Block(fence.toString().trim(), false));
                    fence = null;
                    fenceMarker = null;
                }
                continue;
            }

            if (isFenceStart(trimmed)) {
                flushParagraph(paragraph, blocks);
                fence = new StringBuilder(line);
                fenceMarker = fenceMarkerOf(trimmed);
                continue;
            }

            if (trimmed.isEmpty()) {
                flushParagraph(paragraph, blocks);
                continue;
            }

            if (isHeading(trimmed)) {
                flushParagraph(paragraph, blocks);
                blocks.add(new Block(line.trim(), true));
                continue;
            }

            if (paragraph.length() > 0) {
                paragraph.append('\n');
            }
            paragraph.append(line);
        }

        flushParagraph(paragraph, blocks);
        if (fence != null) {
            // Unclosed fence: keep the remaining content as a block rather
            // than dropping it.
            blocks.add(new Block(fence.toString().trim(), false));
        }
        return blocks;
    }

    private void flushParagraph(StringBuilder paragraph, List<Block> blocks) {
        if (paragraph.length() > 0 && !paragraph.toString().isBlank()) {
            blocks.add(new Block(paragraph.toString().trim(), false));
        }
        paragraph.setLength(0);
    }

    private boolean isHeading(String trimmedLine) {
        int hashes = 0;
        while (hashes < trimmedLine.length() && trimmedLine.charAt(hashes) == '#') {
            hashes++;
        }
        return hashes >= 1 && hashes <= 6
                && hashes < trimmedLine.length()
                && Character.isWhitespace(trimmedLine.charAt(hashes));
    }

    private boolean isFenceStart(String trimmedLine) {
        return trimmedLine.startsWith("```") || trimmedLine.startsWith("~~~");
    }

    private String fenceMarkerOf(String trimmedLine) {
        return trimmedLine.startsWith("```") ? "```" : "~~~";
    }

    private boolean isFenceEnd(String trimmedLine, String marker) {
        return trimmedLine.startsWith(marker);
    }

    private record Block(String text, boolean heading) {
    }
}
