package com.websocket.websocketpoc.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.websocket.websocketpoc.model.LocationModel;
import com.websocket.websocketpoc.model.message.LocationMessage;
import com.websocket.websocketpoc.repository.LocationRepository;
import com.websocket.websocketpoc.service.LocationRedisService;
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
    private final LocationRedisService redisService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${aws.s3.bucket}")
    private String bucketName;

    private final ConcurrentMap<String, LocationModel> latestLocationMap = new ConcurrentHashMap<>();


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

                        // ✅ Save to Redis Stream
                        Mono<String> redisSave = redisService.saveLocationToStream(location)
                                .doOnSuccess(id -> log.info("✅ saved data in redis: {}", id))
                                .doOnError(e -> log.error("❌ Redis me save karne me error", e));


                        return Mono.zip(dbSave, redisSave).doOnSuccess(tuple -> {
                            try {
                                String json = objectMapper.writeValueAsString(List.of(location));
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
                }).then();
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






}
