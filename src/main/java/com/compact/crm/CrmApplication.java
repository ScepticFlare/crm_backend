package com.compact.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling backs the stale-lead auto-invalidation job - see
// scheduler.StaleLeadScheduler.
@SpringBootApplication
@EnableScheduling
public class CrmApplication {

	public static void main(String[] args) {
		SpringApplication.run(CrmApplication.class, args);
	}

}
