package io.github.selectionmenucontrol;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Models Android's memory-first commit and its independent disk generation/receipt. */
public class RuleBackupReceiptTest {
    private static final String OLD = "v3::";
    private static final String NEW = new SystemRuleStore.Snapshot(
            java.util.Collections.singleton("p/p.Hidden")).encode();

    private static final class Storage {
        final Map<String, Object> memory = new HashMap<>();
        final Map<String, Object> disk = new HashMap<>();
        final ArrayDeque<Boolean> receipts = new ArrayDeque<>();
        int commits, diskWrites, memoryGeneration, diskGeneration;
        final SharedPreferences preferences;

        Storage(Boolean... receipts) {
            this.receipts.addAll(Arrays.asList(receipts));
            memory.put(SystemRuleStore.SETTING_KEY, OLD);
            memory.put("global_established", false);
            disk.putAll(memory);
            preferences = (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "getString": case "getBoolean": return memory.getOrDefault(arguments[0], arguments[1]);
                            case "edit": return editor();
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
        private SharedPreferences.Editor editor() {
            Map<String, Object> edits = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "putString": case "putBoolean":
                                edits.put((String) arguments[0], arguments[1]);
                                return proxy;
                            case "commit":
                                commits++;
                                Map<String, Object> next = new HashMap<>(memory);
                                next.putAll(edits);
                                if (!next.equals(memory)) memoryGeneration++;
                                memory.clear(); memory.putAll(next);
                                if (!receipts.removeFirst()) return false;
                                if (diskGeneration < memoryGeneration) {
                                    disk.clear(); disk.putAll(memory);
                                    diskGeneration = memoryGeneration;
                                    diskWrites++;
                                }
                                return true;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    @Test public void failedDiskCommitWithMatchingMemoryIsReallyRetried() {
        Storage storage = new Storage(false, false, true);
        assertFalse(SystemRuleStore.persistBackup(storage.preferences, NEW));
        assertEquals(NEW, storage.memory.get(SystemRuleStore.SETTING_KEY));
        assertEquals(true, storage.memory.get("global_established"));
        assertEquals(OLD, storage.disk.get(SystemRuleStore.SETTING_KEY));
        assertEquals(false, storage.disk.get("global_established"));
        assertTrue(SystemRuleStore.persistBackup(storage.preferences, NEW));
        assertEquals(3, storage.commits);
        assertEquals(NEW, storage.disk.get(SystemRuleStore.SETTING_KEY));
        assertEquals(true, storage.disk.get("global_established"));
    }

    @Test public void matchingMemoryNeverTurnsAnotherDiskFailureIntoSuccess() {
        Storage storage = new Storage(false, false, false, false);
        assertFalse(SystemRuleStore.persistBackup(storage.preferences, NEW));
        assertFalse(SystemRuleStore.persistBackup(storage.preferences, NEW));
        assertEquals(4, storage.commits);
        assertEquals(OLD, storage.disk.get(SystemRuleStore.SETTING_KEY));
    }

    @Test public void successfulSameValueCommitUsesAndroidsConfirmedGenerationWithoutRewritingDisk() {
        Storage storage = new Storage(true, true);
        assertTrue(SystemRuleStore.persistBackup(storage.preferences, NEW));
        assertTrue(SystemRuleStore.persistBackup(storage.preferences, NEW));
        assertEquals(1, storage.diskWrites);
        assertEquals(storage.memory, storage.disk);
    }

    @Test public void secondBoundedAttemptCanConfirmTheFirstMemoryUpdate() {
        Storage storage = new Storage(false, true);
        assertTrue(SystemRuleStore.persistBackup(storage.preferences, NEW));
        assertEquals(2, storage.commits);
        assertEquals(1, storage.diskWrites);
        assertEquals(storage.memory, storage.disk);
    }
}
