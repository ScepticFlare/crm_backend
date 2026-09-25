package com.compact.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling backs the stale-lead auto-invalidation job - see
// scheduler.StaleLeadScheduler. EnableCaching backs the small in-memory
// cache (Spring's default ConcurrentMapCacheManager - no Redis) over
// static master-data lookups (products/industries/lead sources/batteries/
// activity types) that almost every page fetches on load - see the
// @Cacheable getAll() on each of those services.
@SpringBootApplication
@EnableScheduling
@EnableCaching
public class CrmApplication {

	public static void main(String[] args) {
		SpringApplication.run(CrmApplication.class, args);
	}

}
