package com.compact.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling backs the periodic eviction in security.PublicLeadRateLimiter
// (the stale-lead job that used to live here was removed - stale leads are now
// only flagged read-only, see service.StaleLeadRules). EnableCaching backs the small in-memory
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
