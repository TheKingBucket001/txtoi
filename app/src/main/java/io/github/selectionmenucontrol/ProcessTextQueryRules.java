package io.github.selectionmenucontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Applies public query rules without changing the caller's explicit activity options. */
final class ProcessTextQueryRules {
    private ProcessTextQueryRules() {}

    static <T> int specificPrefixLength(List<T> resolved, boolean hasSpecifics,
                                        ToIntFunction<T> specificIndex) {
        if (!hasSpecifics) return 0;
        int count = 0;
        while (count < resolved.size() && specificIndex.applyAsInt(resolved.get(count)) >= 0) {
            count++;
        }
        return count;
    }

    /** The alias callback must return a copy; objects retained from either section stay untouched. */
    static <T> List<T> apply(List<T> resolved, int specificCount, RuleConfig rules,
                             Function<T, String> componentOf, Predicate<T> fixed,
                             BiFunction<T, String, T> withAlias) {
        if (specificCount < 0 || specificCount > resolved.size()) {
            throw new IllegalArgumentException("Invalid specific activity count");
        }
        List<T> filtered = rules.apply(resolved.subList(specificCount, resolved.size()), componentOf, fixed);
        List<T> result = new ArrayList<>(specificCount + filtered.size());
        result.addAll(resolved.subList(0, specificCount));
        if (rules.aliases.isEmpty()) {
            result.addAll(filtered);
        } else {
            for (T entry : filtered) {
                String alias = rules.aliases.get(componentOf.apply(entry));
                result.add(alias == null ? entry : withAlias.apply(entry, alias));
            }
        }
        return result;
    }
}
