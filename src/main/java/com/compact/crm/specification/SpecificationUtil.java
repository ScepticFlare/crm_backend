package com.compact.crm.specification;

import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

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

    // A multi-value filter's selection with nulls and repeats dropped, or
    // null when nothing is left - "no restriction", never an empty IN ()
    // that would silently match nothing.
    public static <V> List<V> selected(Collection<V> values) {

        if (values == null) {
            return null;
        }

        List<V> cleaned = values.stream().filter(Objects::nonNull).distinct().toList();

        return cleaned.isEmpty() ? null : cleaned;
    }
}
