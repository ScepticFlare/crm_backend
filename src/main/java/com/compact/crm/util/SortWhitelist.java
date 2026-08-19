package com.compact.crm.util;

import org.springframework.data.domain.Sort;

import java.util.Map;

/**
 * Translates a frontend-supplied logical sort key (e.g. "company",
 * "assignedEmployee") into a real JPA property path, via a per-module
 * whitelist map. Never builds a Sort/Order from raw frontend input
 * directly - an unrecognized key falls back to the module's default sort
 * rather than being passed through, so there is no way to make the
 * generated query touch a field the whitelist didn't explicitly allow.
 */
public final class SortWhitelist {

    private SortWhitelist() {
    }

    public static Sort resolve(
            String sortBy,
            String sortDir,
            Map<String, String> allowedFields,
            String defaultField
    ) {

        String property = (sortBy != null && allowedFields.containsKey(sortBy))
                ? allowedFields.get(sortBy)
                : allowedFields.getOrDefault(defaultField, defaultField);

        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        return Sort.by(direction, property);
    }
}
