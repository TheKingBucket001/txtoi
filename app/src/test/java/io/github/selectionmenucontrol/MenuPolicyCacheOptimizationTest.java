package io.github.selectionmenucontrol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class MenuPolicyCacheOptimizationTest {
    @Test public void cacheIsNotAliasedToInputsOrReturnedArrays() {
        MenuPolicyCache cache = new MenuPolicyCache();
        int[] kinds = {2, 1, 0};
        int[] order = {1, 0, 2};
        cache.put("policy", kinds, order, 10);
        kinds[0] = 99;
        order[0] = 2;
        MenuPolicyCache.Value first = cache.get("policy", 11);
        assertArrayEquals(new int[]{2, 1, 0}, first.kinds);
        assertArrayEquals(new int[]{1, 0, 2}, first.order);
        first.kinds[0] = 88;
        first.order[0] = 2;
        MenuPolicyCache.Value second = cache.get("policy", 12);
        assertArrayEquals(new int[]{2, 1, 0}, second.kinds);
        assertArrayEquals(new int[]{1, 0, 2}, second.order);
    }

    @Test public void expiryIsAbsoluteRatherThanExtendedByCacheHits() {
        MenuPolicyCache cache = new MenuPolicyCache();
        cache.put("policy", new int[]{1}, new int[]{0}, 100);
        assertNotNull(cache.get("policy", 100 + MenuPolicyCache.TTL_MS - 1));
        assertNull(cache.get("policy", 100 + MenuPolicyCache.TTL_MS));
        assertNull(cache.get("policy", 100 + MenuPolicyCache.TTL_MS + 1));
    }

    @Test public void clockRollbackCannotReuseAnOldPolicy() {
        MenuPolicyCache cache = new MenuPolicyCache();
        cache.put("policy", new int[]{1}, new int[]{0}, 100);
        assertNull(cache.get("policy", 99));
        assertNull(cache.get("policy", 101));
    }

    @Test public void capacityEvictsLeastRecentlyUsedRatherThanFrequentlyUsedPolicy() {
        MenuPolicyCache cache = new MenuPolicyCache();
        for (int index = 0; index < MenuPolicyCache.CAPACITY; index++) {
            cache.put("policy-" + index, new int[]{1}, new int[]{0}, 100);
        }
        assertNotNull(cache.get("policy-0", 101));
        cache.put("new-policy", new int[]{2}, new int[]{0}, 102);
        assertNotNull(cache.get("policy-0", 103));
        assertNull(cache.get("policy-1", 103));
        assertNotNull(cache.get("new-policy", 103));
    }

    @Test public void invalidPermutationsCannotCorruptAnExistingMapping() {
        MenuPolicyCache cache = new MenuPolicyCache();
        cache.put("policy", new int[]{2, 1}, new int[]{1, 0}, 100);
        for (int[] invalid : new int[][]{{0}, {0, 0}, {0, 2}, {-1, 0}}) {
            cache.put("policy", new int[]{1, 1}, invalid, 101);
            assertArrayEquals(new int[]{2, 1}, cache.get("policy", 102).kinds);
            assertArrayEquals(new int[]{1, 0}, cache.get("policy", 102).order);
        }
        cache.put("oversized", new int[RuleConfig.MAX_ENTRIES + 1], new int[RuleConfig.MAX_ENTRIES + 1], 103);
        assertNull(cache.get("oversized", 104));
    }

    @Test public void invalidationDropsAllUsersAndQueryShapes() {
        MenuPolicyCache cache = new MenuPolicyCache();
        cache.put("user-0-shape-A", new int[]{1}, new int[]{0}, 100);
        cache.put("user-10-shape-B", new int[]{2}, new int[]{0}, 100);
        cache.clear();
        assertNull(cache.get("user-0-shape-A", 101));
        assertNull(cache.get("user-10-shape-B", 101));
    }

    @Test public void failureBackoffKeepsOnlyUnknownForTheSameInputAndExpiresWithoutRenewal() {
        MenuPolicyCache cache = new MenuPolicyCache();
        cache.putFailure("user-0-shape-A", 3, 100);
        MenuPolicyCache.Value value = cache.get("user-0-shape-A", 101);
        assertTrue(value.failure);
        assertArrayEquals(new int[]{0, 0, 0}, value.kinds);
        assertArrayEquals(new int[]{0, 1, 2}, value.order);
        assertNull(cache.get("user-0-shape-B", 101));
        assertNull(cache.get("user-10-shape-A", 101));
        assertNotNull(cache.get("user-0-shape-A", 100 + MenuPolicyCache.FAILURE_TTL_MS - 1));
        assertNull(cache.get("user-0-shape-A", 100 + MenuPolicyCache.FAILURE_TTL_MS));
    }

    @Test public void notificationOrSuccessfulRetryCanEndFailureBackoff() {
        MenuPolicyCache cache = new MenuPolicyCache();
        cache.putFailure("policy", 1, 100);
        cache.clear();
        assertNull(cache.get("policy", 101));
        cache.putFailure("policy", 1, 102);
        cache.put("policy", new int[]{2}, new int[]{0}, 103);
        MenuPolicyCache.Value recovered = cache.get("policy", 104);
        assertFalse(recovered.failure);
        assertArrayEquals(new int[]{2}, recovered.kinds);
        assertNotNull(cache.get("policy", 103 + MenuPolicyCache.FAILURE_TTL_MS));
    }
}
