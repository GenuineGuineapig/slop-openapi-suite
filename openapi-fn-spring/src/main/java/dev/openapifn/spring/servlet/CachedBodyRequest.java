package dev.openapifn.spring.servlet;

import dev.openapifn.http.ContentType;
import dev.openapifn.http.Multipart;
import dev.openapifn.http.UrlCodec;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A request whose body has been read into memory, so that it can be validated and still be
 * consumed downstream. Form fields of {@code application/x-www-form-urlencoded} bodies and the
 * parts of {@code multipart/form-data} bodies are served from the cached bytes.
 */
public final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final Optional<ContentType> contentType;
    private @Nullable Map<String, String[]> parameters;
    private @Nullable List<Part> parts;

    public CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body.clone();
        this.contentType = Optional.ofNullable(request.getContentType()).flatMap(ContentType::parse);
    }

    /** Reads the whole body of {@code request} (or reuses an existing cache) and wraps it. */
    public static CachedBodyRequest of(HttpServletRequest request) throws IOException {
        return request instanceof CachedBodyRequest cached ? cached : new CachedBodyRequest(request, request.getInputStream().readAllBytes());
    }

    public byte[] body() {
        return body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        var in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                try {
                    listener.onDataAvailable();
                    listener.onAllDataRead();
                } catch (IOException e) {
                    listener.onError(e);
                }
            }

            @Override
            public int read() {
                return in.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public @Nullable String getParameter(String name) {
        var values = getParameterMap().get(name);
        return values == null ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (parameters == null) {
            parameters = Collections.unmodifiableMap(parameters());
        }
        return parameters;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(getParameterMap().keySet());
    }

    @Override
    public String @Nullable [] getParameterValues(String name) {
        return getParameterMap().get(name);
    }

    private Map<String, String[]> parameters() {
        var merged = new LinkedHashMap<String, List<String>>();
        Optional.ofNullable(getQueryString()).map(UrlCodec::parseQuery).orElse(List.of())
            .forEach(pair -> merged.computeIfAbsent(pair.getKey(), key -> new ArrayList<>()).add(pair.getValue()));
        if (is("application/x-www-form-urlencoded")) {
            UrlCodec.parseQuery(new String(body, charset()))
                .forEach(pair -> merged.computeIfAbsent(pair.getKey(), key -> new ArrayList<>()).add(pair.getValue()));
        } else if (is("multipart/form-data")) {
            for (var part : multipart()) {
                if (part.getSubmittedFileName() == null) {
                    merged.computeIfAbsent(part.getName(), key -> new ArrayList<>()).add(((CachedPart) part).text());
                }
            }
        }
        var result = new LinkedHashMap<String, String[]>();
        merged.forEach((name, values) -> result.put(name, values.toArray(String[]::new)));
        return result;
    }

    @Override
    public Collection<Part> getParts() {
        return multipart();
    }

    @Override
    public @Nullable Part getPart(String name) {
        return multipart().stream().filter(part -> part.getName().equals(name)).findFirst().orElse(null);
    }

    private List<Part> multipart() {
        if (parts == null) {
            var boundary = contentType.filter(type -> type.type().equals("multipart")).flatMap(type -> type.parameter("boundary"));
            parts = boundary.flatMap(b -> Multipart.parse(body, b).toOptional())
                .map(list -> list.stream().<Part>map(CachedPart::new).toList())
                .orElse(List.of());
        }
        return parts;
    }

    private boolean is(String essence) {
        return contentType.filter(type -> type.essence().equals(essence)).isPresent();
    }

    private Charset charset() {
        return contentType.flatMap(type -> type.parameter("charset")).flatMap(name -> {
            try {
                return Optional.of(Charset.forName(name));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }).orElse(StandardCharsets.UTF_8);
    }
}
