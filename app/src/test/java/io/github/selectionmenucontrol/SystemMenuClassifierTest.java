package io.github.selectionmenucontrol;

import android.content.ComponentName;
import android.content.Intent;
import android.view.MenuItem;
import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.assertEquals;

public class SystemMenuClassifierTest {
    static class InstalledToolbar {
        static int generatedNameMayChange(MenuItem left, MenuItem right) { return 0; }
        static boolean unrelated(MenuItem left, MenuItem right) { return false; }
        int instanceMethod(MenuItem left, MenuItem right) { return 0; }
    }

    static class MissingToolbar {
        static int compare(Object left, Object right) { return 0; }
    }

    static class AmbiguousToolbar {
        static int first(MenuItem left, MenuItem right) { return 0; }
        static int second(MenuItem left, MenuItem right) { return 0; }
    }

    static class InstalledPackageManager {
        Object queryIntentActivities(Intent intent, String resolvedType, long flags, int userId) { return null; }
        Object queryIntentActivityOptions(ComponentName caller, Intent[] specifics,
                                          String[] specificTypes, Intent intent,
                                          String resolvedType, long flags, int userId) { return null; }
    }

    @Test public void discoversTheInstalledSignatureWithoutDependingOnLambdaName() throws Exception {
        Method comparator = SystemMenuClassifier.findMenuComparator(InstalledToolbar.class);
        assertEquals("generatedNameMayChange", comparator.getName());
    }

    @Test public void discoversBothPublicProcessTextPackageManagerBoundaries() throws Exception {
        Method activities = SystemMenuClassifier.findQueryActivitiesMethod(InstalledPackageManager.class);
        Method options = SystemMenuClassifier.findQueryActivityOptionsMethod(InstalledPackageManager.class);
        assertEquals("queryIntentActivities", activities.getName());
        assertEquals("queryIntentActivityOptions", options.getName());
    }

    @Test(expected = ReflectiveOperationException.class)
    public void missingComparatorDoesNotInventAnOrder() throws Exception {
        SystemMenuClassifier.findMenuComparator(MissingToolbar.class);
    }

    @Test(expected = ReflectiveOperationException.class)
    public void ambiguousComparatorDoesNotSelectAnArbitraryPolicy() throws Exception {
        SystemMenuClassifier.findMenuComparator(AmbiguousToolbar.class);
    }
}
