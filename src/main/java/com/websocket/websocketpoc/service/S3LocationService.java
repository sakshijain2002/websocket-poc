package com.websocket.websocketpoc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.websocket.websocketpoc.model.LocationModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3LocationService {

    private final S3AsyncClient s3Client;
    private final ObjectMapper objectMapper;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    public Mono<List<LocationModel>> getAllLocationsByPatrollingId(String patrollingId) {
        String prefix = "location-logs/" + patrollingId + "/";

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();

        return Mono.fromFuture(s3Client.listObjectsV2(listRequest))
                .flatMapMany(response -> Flux.fromIterable(response.contents()))
                .flatMap(obj -> getLocationDataByKey(obj.key())) // Mono<List<LocationModel>>
                .flatMap(Flux::fromIterable) // flatten List<LocationModel> to Flux<LocationModel>
                .collectList(); // collect into List<LocationModel>
    }

    public Mono<List<LocationModel>> getLocationDataByKey(String key) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        CompletableFuture<byte[]> futureBytes = s3Client
                .getObject(getRequest, AsyncResponseTransformer.toBytes())
                .thenApply(resp -> resp.asByteArray());

        return Mono.fromFuture(futureBytes)
                .map(bytes -> {
                    try {

                        return objectMapper.readValue(
                                bytes,
                                new TypeReference<List<LocationModel>>() {}
                        );
                    } catch (Exception e) {
                        log.warn("Failed to parse file from key: {}", key, e);
                        return new ArrayList<LocationModel>();
                    }
                })
                .onErrorResume(ex -> {
                    log.warn("S3 fetch failed for key: {}", key, ex);
                    return Mono.just(new ArrayList<>());
                });
    }

    public Mono<List<LocationModel>> getLocationDataByUser(String patrollingId, String userId) {
        String prefix = String.format("location-logs/%s/", patrollingId);

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();

        return Mono.fromFuture(s3Client.listObjectsV2(listRequest))
                .flatMapMany(response -> Flux.fromIterable(response.contents()))
                .filter(obj -> obj.key().contains("/" + userId + "-")) // Only that user's files
                .flatMap(obj -> getLocationDataByKey(obj.key()))       // Read each file
                .flatMap(Flux::fromIterable)                           // Flatten list
                .sort(Comparator.comparing(LocationModel::getTimestamp)) // Optional: sort
                .collectList();
    }

}
