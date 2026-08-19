package com.compact.crm.specification;

import org.springframework.data.jpa.domain.Specification;

import java.util.List;

/**
 * Small combinator shared by every module's *Specifications class - folds a
 * list of optional (possibly null) predicates into one, so each module only
 * has to build small single-purpose Specification methods and never has to
 * hand-write the null-skipping AND-chaining itself.
 */
public final class SpecificationUtil {

    private SpecificationUtil() {
    }

    public static <T> Specification<T> and(List<Specification<T>> specs) {

        Specification<T> combined = null;

        for (Specification<T> spec : specs) {

            if (spec == null) {
                continue;
            }

            combined = (combined == null) ? spec : combined.and(spec);
        }

        return combined;
    }
}
