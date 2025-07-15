package com.websocket.websocketpoc.controller;

import com.websocket.websocketpoc.service.S3PresignedUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin
public class PatrolLocationController {

    private final S3PresignedUrlService urlService;

    @GetMapping("/urls")
    public Mono<List<String>> getUrls(@RequestParam String patrollingId) {
        return urlService.getUrlsByPatrollingId(patrollingId);
    }
}
