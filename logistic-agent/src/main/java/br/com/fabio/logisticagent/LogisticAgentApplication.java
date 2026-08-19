package br.com.fabio.logisticagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class LogisticAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(LogisticAgentApplication.class, args);
	}

}
