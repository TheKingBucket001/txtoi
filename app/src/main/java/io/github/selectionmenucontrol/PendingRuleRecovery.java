package io.github.selectionmenucontrol;

import java.util.Objects;

/** Replay requires the original baseline; unrelated settings always win over a private draft. */
final class PendingRuleRecovery {
    enum Action { WAIT, COMPLETE, REPLAY, CONFLICT }
    static Action decide(String baseline, String target, String current, boolean readable) {
        return decide(baseline, target, null, current, readable);
    }
    static Action decide(String baseline, String target, String inFlight, String current, boolean readable) {
        if (!readable) return Action.WAIT;
        if (Objects.equals(target, current)) return Action.COMPLETE;
        return Objects.equals(baseline, current) || (inFlight != null && Objects.equals(inFlight, current))
                ? Action.REPLAY : Action.CONFLICT;
    }
}
