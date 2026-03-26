package com.manhwa.manhwa_translator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

	static {
		nu.pattern.OpenCV.loadLocally();
	}

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}
