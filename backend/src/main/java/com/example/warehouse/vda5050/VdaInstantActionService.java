package com.example.warehouse.vda5050;

import com.example.warehouse.vda.Vda5050;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Makes repeated terminal instant-action snapshots idempotent. */
public final class VdaInstantActionService {
  private final Set<String> handledActionIds = ConcurrentHashMap.newKeySet();

  public boolean consumeFinished(Vda5050.State state, String actionType) {
    String actionId = state.instantActionStates().stream()
        .filter(action -> actionType.equals(action.get("actionType"))
            && "FINISHED".equals(action.get("actionStatus")))
        .map(action -> String.valueOf(action.get("actionId")))
        .filter(id -> !handledActionIds.contains(id))
        .findFirst().orElse(null);
    if (actionId == null) return false;
    if (handledActionIds.size() > 512) handledActionIds.clear();
    return handledActionIds.add(actionId);
  }
}
