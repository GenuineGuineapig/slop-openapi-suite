package dev.openapifn.spring.servlet;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.Multipart;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** The parts of an already parsed multipart request: servlet parts plus Spring multipart files. */
final class MultipartParts {

    private MultipartParts() {
    }

    static List<Multipart.Part> of(HttpServletRequest request) throws IOException {
        var result = new ArrayList<Multipart.Part>();
        var seen = new HashSet<String>();
        try {
            for (var part : request.getParts()) {
                seen.add(part.getName() + "/" + part.getSubmittedFileName());
                result.add(BodyWriter.part(part.getName(), Optional.ofNullable(part.getSubmittedFileName()), Optional.ofNullable(part.getContentType()),
                    part.getInputStream().readAllBytes()));
            }
        } catch (ServletException e) {
            throw new IOException(e);
        }
        if (request instanceof MultipartHttpServletRequest multipart) {
            for (var file : multipart.getMultiFileMap().values().stream().flatMap(List::stream).toList()) {
                if (seen.add(file.getName() + "/" + file.getOriginalFilename())) {
                    result.add(BodyWriter.part(file.getName(), Optional.ofNullable(file.getOriginalFilename()), Optional.ofNullable(file.getContentType()),
                        file.getBytes()));
                }
            }
        }
        return result;
    }
}
