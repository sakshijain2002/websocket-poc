package com.websocket.websocketpoc.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.websocket.websocketpoc.model.LocationModel;
import com.websocket.websocketpoc.model.message.LocationMessage;
import com.websocket.websocketpoc.repository.LocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocationWebSocketHandler {

    private final List<FluxSink<String>> frontendSinks = new CopyOnWriteArrayList<>();
    private final LocationRepository locationRepository;
    private final S3AsyncClient s3AsyncClient;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${aws.s3.bucket}")
    private String bucketName;

    private final ConcurrentMap<String, LocationModel> latestLocationMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<LocationModel>> bufferedForS3 = new ConcurrentHashMap<>();

    public WebSocketHandler appHandler() {
        return session -> session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(payload -> {
                    try {
                        LocationMessage msg = objectMapper.readValue(payload, LocationMessage.class);
                        String sessionUserKey = msg.getPatrollingId() + "-" + msg.getUserId();

                        LocationModel location = LocationModel.builder()
                                .userId(msg.getUserId())
                                .latitude(msg.getLatitude())
                                .longitude(msg.getLongitude())
                                .timestamp(LocalDateTime.now())
                                .patrollingId(msg.getPatrollingId())
                                .build();

                        latestLocationMap.put(sessionUserKey, location);
                        Mono<LocationModel> dbSave = locationRepository.save(location);

                        bufferedForS3.computeIfAbsent(sessionUserKey, k -> new ArrayList<>()).add(location);

                        return dbSave.doOnSuccess(saved -> {
                            try {
                                String json = objectMapper.writeValueAsString(List.of(saved));
                                frontendSinks.forEach(sink -> {
                                    if (!sink.isCancelled()) sink.next(json);
                                });
                            } catch (JsonProcessingException ex) {
                                log.error("Error serializing message for frontend", ex);
                            }
                        }).then();
                    } catch (Exception e) {
                        log.error("Failed to process incoming message", e);
                        return Mono.empty();
                    }
                })
                .then();
    }

    public WebSocketHandler frontendHandler() {
        return session -> {
            Flux<String> output = Flux.<String>create(sink -> {
                frontendSinks.add(sink);

                locationRepository.findAll()
                        .collectList()
                        .flatMap(list -> {
                            try {
                                return Mono.just(objectMapper.writeValueAsString(list));
                            } catch (JsonProcessingException e) {
                                log.error("Error serializing initial data for frontend", e);
                                return Mono.empty();
                            }
                        })
                        .subscribe(sink::next, error -> log.error("Error sending data to frontend", error));

                sink.onDispose(() -> {
                    frontendSinks.remove(sink);
                    log.info("Frontend WebSocket disconnected");
                });
            });

            return session.send(output.map(session::textMessage));
        };
    }

    public void persistsBufferedDataToS3() {
        bufferedForS3.forEach((key, newLocations) -> {
            if (newLocations.isEmpty()) return;

            String[] parts = key.split("-", 2);
            String patrollingId = parts[0];
            String userId = parts[1];
            String keyPath = String.format("location-logs/%s/%s.json", patrollingId, userId);

            getExistingLocationsFromS3(keyPath)
                    .defaultIfEmpty(new ArrayList<>())
                    .flatMap(existingLocations -> {
                        existingLocations.addAll(newLocations);
                        try {
                            String mergedJson = objectMapper.writeValueAsString(existingLocations);
                            return uploadToS3(keyPath, mergedJson)
                                    .doOnSuccess(resp -> {
                                        log.info("Successfully uploaded {} entries to S3 at {}", existingLocations.size(), keyPath);
                                        newLocations.clear();
                                    });
                        } catch (JsonProcessingException e) {
                            log.error("Error serializing merged locations", e);
                            return Mono.empty();
                        }
                    })
                    .subscribe(); // trigger the chain
        });
    }

    private Mono<List<LocationModel>> getExistingLocationsFromS3(String keyPath) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(keyPath)
                .build();

        return Mono.fromFuture(s3AsyncClient.getObject(getRequest, AsyncResponseTransformer.toBytes()))
                .flatMap(responseBytes -> {
                    String json = responseBytes.asUtf8String();
                    try {
                        List<LocationModel> locations = objectMapper.readValue(json, new TypeReference<>() {});
                        return Mono.just(locations);
                    } catch (JsonProcessingException e) {
                        log.error("Error parsing JSON from S3 for key: {}", keyPath, e);
                        return Mono.empty(); // or fallback to emptyList()
                    }
                })
                .onErrorResume(NoSuchKeyException.class, ex -> {
                    log.info("No existing S3 file found for {}, creating new", keyPath);
                    return Mono.empty();
                })
                .onErrorResume(ex -> {
                    log.warn("Failed to fetch or parse existing S3 file for {}", keyPath, ex);
                    return Mono.empty();
                });
    }


    private Mono<PutObjectResponse> uploadToS3(String keyPath, String json) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(keyPath)
                .contentType("application/json")
                .build();

        return Mono.fromFuture(
                s3AsyncClient.putObject(putRequest, AsyncRequestBody.fromString(json))
        );
    }
}
