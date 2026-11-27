package com.commercehub.events;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReplayLimiter {
  private record Budget(long expires, int attempts) {}
  private final Map<UUID, Budget> budgets = new HashMap<>();

  public synchronized boolean allow(UUID actor, long now) {
    budgets.entrySet().removeIf(entry -> entry.getValue().expires <= now);
    Budget budget = budgets.get(actor);
    if (budget == null) {
      if (budgets.size() >= 4096) return false;
      budgets.put(actor, new Budget(now + 60_000, 1));
      return true;
    }
    if (budget.attempts >= 5) return false;
    budgets.put(actor, new Budget(budget.expires, budget.attempts + 1));
    return true;
  }
}
