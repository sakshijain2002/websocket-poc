package com.websocket.websocketpoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.websocket.websocketpoc.model.LocationModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
public class LocationRedisService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${location.redis.stream.key:location-stream}")
    private String redisStreamKey;

    public LocationRedisService(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = reactiveStringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public Mono<String> saveLocationToStream(LocationModel location) {
        Map<String, String> map = Map.of(
                "userId", location.getUserId(),
                "latitude", String.valueOf(location.getLatitude()),
                "longitude", String.valueOf(location.getLongitude()),
                "timestamp", location.getTimestamp().toString(),
                "patrollingId", location.getPatrollingId(),
                "routeId", location.getRouteId()
        );

        log.info("📝 Writing to Redis Stream: {}", map);

        return redisTemplate.<String, String>opsForStream()
                .add(MapRecord.create(redisStreamKey, map))
                .map(Object::toString)
                .doOnSuccess(id -> log.info("✅ data write in stream: {}", id))
                .doOnError(e -> log.error("❌ Redis Stream write fail", e));
    }

}
