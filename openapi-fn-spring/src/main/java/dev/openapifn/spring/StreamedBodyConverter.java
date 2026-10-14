package dev.openapifn.spring;

import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpResponse;

/** Writes {@link StreamedBody} responses item by item, flushing each; registered by the auto-configuration. */
public final class StreamedBodyConverter implements HttpMessageConverter<StreamedBody> {

    @Override
    public boolean canRead(Class<?> clazz, @Nullable MediaType mediaType) {
        return false;
    }

    @Override
    public boolean canWrite(Class<?> clazz, @Nullable MediaType mediaType) {
        return StreamedBody.class.isAssignableFrom(clazz);
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return List.of(MediaType.ALL);
    }

    @Override
    public StreamedBody read(Class<? extends StreamedBody> clazz, HttpInputMessage inputMessage) {
        throw new UnsupportedOperationException("Streamed bodies are only written");
    }

    @Override
    public void write(StreamedBody body, @Nullable MediaType contentType, HttpOutputMessage outputMessage) throws IOException {
        if (outputMessage.getHeaders().getContentType() == null && contentType != null && contentType.isConcrete()) {
            outputMessage.getHeaders().setContentType(contentType);
        }
        var out = outputMessage.getBody();
        // Server responses hand out a body that ignores flushing; the response itself flushes.
        body.writeTo(out, outputMessage instanceof ServerHttpResponse response ? response::flush : out);
    }
}
