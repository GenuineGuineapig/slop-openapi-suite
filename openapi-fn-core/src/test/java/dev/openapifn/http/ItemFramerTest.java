package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ItemFramerTest {

    private static ContentType type(String text) {
        return ContentType.parse(text).orElseThrow();
    }

    private static ItemFramer framer(String type) {
        return ItemFramer.of(type(type)).orElseThrow();
    }

    /** The contents of the frames, fed in chunks of {@code chunk} bytes; checks that the raw bytes add up to the body. */
    private static List<String> contents(String type, String body, int chunk) {
        var framer = framer(type);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var frames = new ArrayList<ItemFramer.Frame>();
        for (int i = 0; i < bytes.length; i += chunk) {
            frames.addAll(framer.feed(bytes, i, Math.min(chunk, bytes.length - i)));
        }
        frames.add(framer.finish());
        var raw = new ByteArrayOutputStream();
        frames.forEach(frame -> raw.writeBytes(frame.raw()));
        assertThat(raw.toString(StandardCharsets.UTF_8)).isEqualTo(body);
        return frames.stream().map(frame -> new String(frame.content(), StandardCharsets.UTF_8) + frame.error().map(e -> "!" + e).orElse("")).toList();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 1000})
    void framesLines(int chunk) {
        assertThat(contents("application/jsonl", "{\"a\":1}\r\n\n{\"b\":2}\n[3]", chunk)).containsExactly("{\"a\":1}", "", "{\"b\":2}", "[3]");
        assertThat(contents("application/x-ndjson", "1\n", chunk)).containsExactly("1", "");
        assertThat(contents("application/x-jsonlines", "", chunk)).containsExactly("");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 1000})
    void framesJsonTextSequences(int chunk) {
        var body = "\u001e{\"a\": \"}]\\\" {\"}\n\u001e[\n  1,\n  {\"b\": [2]}\n]\n\u001e3\u001e\"x\"\n\u001e\n\u001e{\"c\":";
        assertThat(contents("application/json-seq", body, chunk)).containsExactly("{\"a\": \"}]\\\" {\"}", "[\n  1,\n  {\"b\": [2]}\n]", "3", "\"x\"",
            "{\"c\":");
        assertThat(contents("application/json-seq", "\u001e1\n", chunk)).containsExactly("1", "");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 1000})
    void framesEvents(int chunk) {
        assertThat(contents("text/event-stream", ": hi\r\nevent: a\r\ndata: 1\r\n\r\n\ndata: 2\n\nid: 3", chunk))
            .containsExactly(": hi\r\nevent: a\r\ndata: 1\r\n", "", "data: 2\n", "id: 3");
        assertThat(contents("text/event-stream", "\r\n", chunk)).containsExactly("", "");
        assertThat(contents("text/event-stream", "x\n\n", chunk)).containsExactly("x\n", "");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 1000})
    void framesParts(int chunk) {
        var body = "preamble\r\n--b\r\nContent-Type: text/plain\r\n\r\none\r\n--b  \r\n\r\ntwo --b\nx--b\r\n--b\nX: 1\n\nthree\n--b--\r\nepilogue";
        assertThat(contents("multipart/mixed; boundary=b", body, chunk))
            .containsExactly("Content-Type: text/plain\r\n\r\none", "\r\ntwo --b\nx--b", "X: 1\n\nthree", "");
        assertThat(contents("multipart/related; boundary=b", "--b\r\n\r\n--b--", chunk)).containsExactly("", "");
        assertThat(contents("multipart/mixed; boundary=b", "--b\n--b--", chunk)).containsExactly("", "");
        assertThat(contents("multipart/mixed; boundary=b", "--b-\r\n\r\nx\r\n--b--", chunk)).containsExactly("\r\nx", "");
        assertThat(contents("multipart/mixed; boundary=b", "--b\r\n\r\nx\r\n--b", chunk))
            .containsExactly("\r\nx", "!multipart body is not terminated by the closing boundary");
        assertThat(contents("multipart/mixed; boundary=b", "--b\r\n\r\nx", chunk))
            .containsExactly("!multipart body is not terminated by the closing boundary");
        assertThat(contents("multipart/mixed; boundary=b", "x--b\r\n", chunk)).containsExactly("!multipart body does not contain the boundary 'b'");
    }

    @Test
    void keepsLargeItemsWhileDroppingConsumedBytes() {
        var big = "x".repeat(2000);
        var lines = big + "\n" + big + "\n" + "y".repeat(700) + "\n";
        assertThat(contents("application/jsonl", lines, 300)).containsExactly(big, big, "y".repeat(700), "");
        var parts = "--b\r\n\r\n" + big + "\r\n--b\r\n\r\n" + big + "\r\n--b\r\n\r\n" + big + "\r\n--b--";
        assertThat(contents("multipart/mixed; boundary=b", parts, 300)).containsExactly("\r\n" + big, "\r\n" + big, "\r\n" + big, "");
        assertThat(contents("multipart/mixed; boundary=b", "--b\n\n" + big + "\n--b--", 700)).containsExactly("\n" + big, "");
    }

    @Test
    void framesWholeBodies() {
        var frames = framer("application/jsonl").frames("1\n2".getBytes(StandardCharsets.UTF_8));
        assertThat(frames).extracting(frame -> new String(frame.content(), StandardCharsets.UTF_8)).containsExactly("1", "2");
        assertThat(frames.getFirst().blank()).isFalse();
        assertThat(new ItemFramer.Frame(new byte[0], " \t\r\n".getBytes(StandardCharsets.UTF_8), Optional.empty()).blank()).isTrue();
        assertThat(framer("application/jsonl").feed("1\n".getBytes(StandardCharsets.UTF_8))).hasSize(1);
    }

    @Test
    void knowsWhichTypesAreSequential() {
        assertThat(List.of("application/jsonl", "application/x-ndjson; charset=utf-8", "application/json-seq", "text/event-stream",
            "multipart/mixed; boundary=b", "multipart/byteranges; boundary=b")).allMatch(text -> ItemFramer.isSequential(type(text)));
        assertThat(List.of("application/json", "text/plain", "multipart/form-data; boundary=b", "multipart/mixed"))
            .noneMatch(text -> ItemFramer.isSequential(type(text)));
        assertThat(List.of("application/jsonl", "application/x-ndjson", "application/x-jsonlines", "application/json-seq", "text/event-stream",
            "multipart/mixed")).allMatch(text -> ItemFramer.isSequentialMediaType(type(text)));
        assertThat(List.of("application/json", "text/plain", "multipart/form-data", "multipart/*", "*/*"))
            .noneMatch(text -> ItemFramer.isSequentialMediaType(type(text)));
    }
}
