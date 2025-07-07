package com.websocket.websocketpoc.repository;

import com.websocket.websocketpoc.model.LocationModel;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface LocationRepository extends ReactiveCrudRepository<LocationModel, String> {
    Mono<LocationModel> findByUserId(String userId);
}
