package com.websocket.websocketpoc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3PresignedUrlService {

    private final S3AsyncClient s3AsyncClient;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucketName;
    @Value("${aws.s3.presigned-url-duration-minutes}")
    private long presignExpiryMinutes;

    public Mono<List<String>> getUrlsByPatrollingId(String patrollingId) {
        String prefix = String.format("DriverLocation/%s/", patrollingId);

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();

        return Mono.fromFuture(() -> s3AsyncClient.listObjectsV2(listRequest))
                .flatMapMany(response -> Flux.fromIterable(
                        response.contents().stream()
                                .sorted(Comparator.comparing(S3Object::lastModified).reversed()) //  sort here
                                .toList()
                ))
                .filter(obj -> obj.key().endsWith(".json"))
                .flatMap(obj -> Mono.fromCallable(() -> generatePresignedUrl(obj.key())))
                .collectList();
    }


    private String generatePresignedUrl(String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(presignExpiryMinutes))
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }
}
