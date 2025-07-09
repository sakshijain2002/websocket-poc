package com.websocket.websocketpoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WebsocketpocApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebsocketpocApplication.class, args);
	}

}
