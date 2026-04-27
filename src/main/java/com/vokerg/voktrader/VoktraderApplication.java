package com.vokerg.voktrader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VoktraderApplication {

	public static void main(String[] args) {
		SpringApplication.run(VoktraderApplication.class, args);
	}

}
