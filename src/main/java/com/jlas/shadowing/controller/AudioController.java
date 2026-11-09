package com.jlas.shadowing.controller;

import com.jlas.shadowing.service.ShadowingBlobStore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@Controller
@RequiredArgsConstructor
public class AudioController {
    private final ShadowingBlobStore blobStore;

    @RequestMapping(path = "/audio/**", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Resource> audio(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/audio/")) return ResponseEntity.notFound().build();
        String name = path.substring("/audio/".length());
        if (!ShadowingBlobStore.isSafeAudioName(name)) return ResponseEntity.notFound().build();

        Resource resource = new ClassPathResource("static/audio/" + name);
        if (resource.exists()) {
            MediaType contentType = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().contentType(contentType).body(resource);
        }
        return blobStore.signedAudioUrl(name)
                .map(url -> ResponseEntity.status(HttpStatus.FOUND).location(url)
                        .cacheControl(CacheControl.noStore()).<Resource>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
