package com.websocket.websocketpoc.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.websocket.websocketpoc.model.LocationModel;
import com.websocket.websocketpoc.service.S3LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/s3")
@RequiredArgsConstructor
@Slf4j
public class LocationS3Controller {

    private final S3AsyncClient s3Client;
    private final ObjectMapper objectMapper;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    private final S3LocationService s3LocationService;

    @GetMapping("/locations/{patrollingId}")
    public Mono<List<LocationModel>> getAllLocationsByPatrollingId(@PathVariable String patrollingId) {
        return s3LocationService.getAllLocationsByPatrollingId(patrollingId);
    }

    @GetMapping("/locations/{patrollingId}/{userId}")
    public Mono<List<LocationModel>> getLocationDataFromS3(
            @PathVariable String patrollingId,
            @PathVariable String userId
    ) {
        return s3LocationService.getLocationDataByUser(patrollingId, userId);
    }



}
