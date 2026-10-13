package dev.openapifn.spring.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;
import org.springframework.web.util.ContentCachingResponseWrapper;

class ServletSupportTest {

    @TempDir
    Path dir;

    private static MockHttpServletRequest request(String contentType, String body) {
        var request = new MockHttpServletRequest("POST", "/ctx/items");
        request.setContextPath("/ctx");
        request.setQueryString("q=1&q=2");
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void cachesBodiesAndServesStreamsAndReaders() throws IOException {
        var cached = CachedBodyRequest.of(request("text/plain; charset=ISO-8859-1", "héllo"));
        assertThat(CachedBodyRequest.of(cached)).isSameAs(cached);
        assertThat(cached.getInputStream().readAllBytes()).isEqualTo("héllo".getBytes(StandardCharsets.UTF_8));
        assertThat(cached.getInputStream().readAllBytes()).hasSize(6);
        assertThat(cached.getContentLength()).isEqualTo(6);
        assertThat(cached.getContentLengthLong()).isEqualTo(6);
        assertThat(cached.getReader().readLine()).isEqualTo("hÃ©llo");
        assertThat(CachedBodyRequest.of(request("text/plain; charset=unknown", "x")).getReader().readLine()).isEqualTo("x");
        assertThat(CachedBodyRequest.of(request("text/plain", "x")).getReader().readLine()).isEqualTo("x");
        var stream = cached.getInputStream();
        assertThat(stream.isReady()).isTrue();
        assertThat(stream.isFinished()).isFalse();
        stream.readAllBytes();
        assertThat(stream.isFinished()).isTrue();
        var events = new ArrayList<String>();
        stream.setReadListener(new ReadListener() {
            @Override
            public void onDataAvailable() {
                events.add("data");
            }

            @Override
            public void onAllDataRead() {
                events.add("done");
            }

            @Override
            public void onError(Throwable t) {
                events.add("error");
            }
        });
        stream.setReadListener(new ReadListener() {
            @Override
            public void onDataAvailable() throws IOException {
                throw new IOException("boom");
            }

            @Override
            public void onAllDataRead() {
                events.add("unexpected");
            }

            @Override
            public void onError(Throwable t) {
                events.add("error:" + t.getMessage());
            }
        });
        assertThat(events).containsExactly("data", "done", "error:boom");
        assertThat(cached.body()).hasSize(6);
    }

    @Test
    void servesFormParameters() throws IOException {
        var form = CachedBodyRequest.of(request("application/x-www-form-urlencoded", "a=1&q=3&b=x+y"));
        assertThat(form.getParameter("a")).isEqualTo("1");
        assertThat(form.getParameterValues("q")).containsExactly("1", "2", "3");
        assertThat(form.getParameter("b")).isEqualTo("x y");
        assertThat(form.getParameter("missing")).isNull();
        assertThat(Collections.list(form.getParameterNames())).containsExactly("q", "a", "b");
        assertThat(form.getParameterMap()).isSameAs(form.getParameterMap());
        var noQuery = new MockHttpServletRequest("POST", "/x");
        noQuery.setContentType("application/json");
        noQuery.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(CachedBodyRequest.of(noQuery).getParameterMap()).isEmpty();
        var noType = new MockHttpServletRequest("POST", "/x");
        assertThat(CachedBodyRequest.of(noType).getParts()).isEmpty();
    }

    @Test
    void servesMultipartParts() throws IOException {
        var body = "--b\r\nContent-Disposition: form-data; name=text\r\n\r\nhi\r\n--b\r\nContent-Disposition: form-data; name=file; filename=f.txt\r\n"
            + "Content-Type: text/plain\r\nX-Extra: 1\r\n\r\nfile content\r\n--b--";
        var multipart = CachedBodyRequest.of(request("multipart/form-data; boundary=b", body));
        assertThat(multipart.getParameter("text")).isEqualTo("hi");
        assertThat(multipart.getParameter("file")).isNull();
        assertThat(multipart.getParts()).hasSize(2);
        var file = multipart.getPart("file");
        assertThat(file.getName()).isEqualTo("file");
        assertThat(file.getSubmittedFileName()).isEqualTo("f.txt");
        assertThat(file.getContentType()).isEqualTo("text/plain");
        assertThat(file.getSize()).isEqualTo(12);
        assertThat(file.getInputStream().readAllBytes()).isEqualTo("file content".getBytes(StandardCharsets.UTF_8));
        assertThat(file.getHeader("x-extra")).isEqualTo("1");
        assertThat(file.getHeaders("X-Extra")).containsExactly("1");
        assertThat(file.getHeaderNames()).contains("content-disposition", "x-extra");
        var target = dir.resolve("out.txt");
        file.write(target.toString());
        assertThat(Files.readString(target)).isEqualTo("file content");
        file.delete();
        var text = multipart.getPart("text");
        assertThat(text.getContentType()).isNull();
        assertThat(text.getSubmittedFileName()).isNull();
        assertThat(multipart.getPart("missing")).isNull();
        var nameless = CachedBodyRequest.of(request("multipart/form-data; boundary=b", "--b\r\nX: y\r\n\r\nz\r\n--b--"));
        assertThat(nameless.getParts().iterator().next().getName()).isEmpty();
        assertThat(CachedBodyRequest.of(request("multipart/form-data", body)).getParts()).isEmpty();
        assertThat(CachedBodyRequest.of(request("multipart/form-data; boundary=zz", body)).getParts()).isEmpty();
        assertThat(CachedBodyRequest.of(request("application/json", "{}")).getParts()).isEmpty();
    }

    @Test
    void reencodesParsedMultipartRequests() throws IOException {
        var mock = new MockMultipartHttpServletRequest();
        mock.setRequestURI("/items");
        mock.setContentType("multipart/form-data; boundary=ignored");
        mock.addPart(new MockPart("meta", "{}".getBytes(StandardCharsets.UTF_8)));
        mock.addPart(new MockPart("doc", "d.txt", "abc".getBytes(StandardCharsets.UTF_8)));
        mock.addFile(new MockMultipartFile("doc", "d.txt", "text/plain", "abc".getBytes(StandardCharsets.UTF_8)));
        mock.addFile(new MockMultipartFile("img", "i.png", "image/png", new byte[] {1}));
        var cached = CachedBodyRequest.of(mock);
        assertThat(cached.getContentType()).startsWith("multipart/form-data; boundary=openapi-fn-");
        assertThat(cached.getHeader("content-type")).isEqualTo(cached.getContentType());
        assertThat(Collections.list(cached.getHeaders("Content-Type"))).containsExactly(cached.getContentType());
        assertThat(cached.getParts()).extracting(Part::getName).containsExactly("meta", "doc", "img");
        var message = ServletMessages.toHttpRequest(mock);
        assertThat(message.contentType().orElseThrow()).startsWith("multipart/form-data; boundary=openapi-fn-");
        var plain = new MockHttpServletRequest("POST", "/x");
        plain.setContentType("multipart/form-data; boundary=x");
        plain.addHeader("X-Other", "1");
        assertThat(CachedBodyRequest.of(plain).getHeader("X-Other")).isEqualTo("1");
        var failing = new MockHttpServletRequest("POST", "/x") {
            @Override
            public Collection<Part> getParts() throws ServletException {
                throw new ServletException("broken");
            }
        };
        failing.setContentType("multipart/form-data; boundary=x");
        assertThatThrownBy(() -> ServletMessages.toHttpRequest(failing)).isInstanceOf(UncheckedIOException.class).hasRootCauseMessage("broken");
    }

    @Test
    void convertsMessages() throws IOException {
        var request = request("application/json", "{}");
        request.addHeader("X-Multi", "a");
        request.addHeader("X-Multi", "b");
        var message = ServletMessages.toHttpRequest(request);
        assertThat(message.path()).isEqualTo("/items");
        assertThat(message.query()).isEqualTo("q=1&q=2");
        assertThat(message.headers().all("x-multi")).containsExactly("a", "b");
        assertThat(message.body()).isPresent();
        var empty = new MockHttpServletRequest("GET", "/x");
        assertThat(ServletMessages.toHttpRequest(empty).body()).isEmpty();
        assertThat(ServletMessages.toHttpRequest(empty).query()).isEmpty();
        var wrapped = new jakarta.servlet.http.HttpServletRequestWrapper(CachedBodyRequest.of(request("text/plain", "cached")));
        assertThat(new String(ServletMessages.toHttpRequest(wrapped).body().orElseThrow(), StandardCharsets.UTF_8)).isEqualTo("cached");
        var response = new ContentCachingResponseWrapper(new MockHttpServletResponse());
        response.setStatus(201);
        response.addHeader("X-A", "1");
        response.setContentType("text/plain");
        response.getWriter().write("ok");
        response.getWriter().flush();
        var converted = ServletMessages.response(response);
        assertThat(converted.status()).isEqualTo(201);
        assertThat(converted.headers().first("x-a")).contains("1");
        assertThat(converted.contentType()).contains("text/plain");
        assertThat(converted.body()).isPresent();
        var hiding = new MockHttpServletResponse() {
            @Override
            public Collection<String> getHeaderNames() {
                return super.getHeaderNames().stream().filter(name -> !name.equalsIgnoreCase("Content-Type")).toList();
            }
        };
        var tomcatLike = new ContentCachingResponseWrapper(hiding);
        tomcatLike.setContentType("application/json");
        assertThat(ServletMessages.response(tomcatLike).contentType()).contains("application/json");
        var bare = new ContentCachingResponseWrapper(new MockHttpServletResponse());
        assertThat(ServletMessages.response(bare).body()).isEmpty();
        assertThat(ServletMessages.response(bare).contentType()).isEmpty();
        var throwing = new jakarta.servlet.http.HttpServletRequestWrapper(new MockHttpServletRequest("POST", "/x")) {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
                throw new IOException("closed");
            }
        };
        assertThatThrownBy(() -> ServletMessages.toHttpRequest(throwing)).isInstanceOf(UncheckedIOException.class).hasRootCauseMessage("closed");
        assertThat(List.of(dir)).isNotEmpty();
    }
}
