package com.websocket.websocketpoc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
public class WebSocketRouter {

    @Bean
    public HandlerMapping handlerMapping(LocationWebSocketHandler handler) {
        return new SimpleUrlHandlerMapping() {{
            setOrder(10);
            setUrlMap(Map.of(
                    "/app-stream", handler.appHandler(),
                    "/frontend-stream", handler.frontendHandler()
            ));
        }};
    }

    @Bean
    public WebSocketHandlerAdapter adapter() {
        return new WebSocketHandlerAdapter();
    }
}