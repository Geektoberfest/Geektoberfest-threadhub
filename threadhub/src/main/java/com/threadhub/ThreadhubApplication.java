package com.threadhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ThreadhubApplication {

	public static void main(String[] args) {
		SpringApplication.run(ThreadhubApplication.class, args);
	}

}
