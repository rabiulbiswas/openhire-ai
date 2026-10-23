package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.exception.AiExceptions.BudgetExhausted;
import com.openhire.aigateway.service.port.AiPorts.BudgetAlert;
import com.openhire.aigateway.service.port.AiPorts.Usage;
import com.openhire.aigateway.service.port.AiPorts.UsageStore;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Prices calls (INR per million tokens, zero for local models), keeps monthly totals per tenant and task, raises a
 * soft alert at 80 percent of the budget and enforces the hard cap on non-local providers.
 */
public class CostMeter {
    public record Price(double promptPerMillion, double completionPerMillion) {}

    private static final Price UNKNOWN_HOSTED = new Price(100, 300);
    private final Map<String, Price> prices;
    private final UsageStore usage;
    private final Clock clock;

    public CostMeter(Map<String, Price> overrides, UsageStore usage, Clock clock) {
        this.prices = new HashMap<>(defaults());
        this.prices.putAll(overrides);
        this.usage = usage;
        this.clock = clock;
    }

    /** Indicative list prices converted to INR; override with openhire.ai.prices. */
    private static Map<String, Price> defaults() {
        return Map.of(
                "qwen2.5:3b-instruct", new Price(0, 0),
                "nomic-embed-text", new Price(0, 0),
                "gpt-4o-mini", new Price(13, 50),
                "text-embedding-3-small", new Price(2, 0));
    }

    /** Parses "model=promptPrice:completionPrice,model2=..." (INR per million tokens). */
    public static Map<String, Price> parse(String text) {
        var out = new HashMap<String, Price>();
        if (text == null || text.isBlank()) return out;
        for (var item : text.split(",")) {
            var kv = item.split("=", 2);
            if (kv.length != 2) throw new IllegalArgumentException("Bad price entry: " + item);
            var parts = kv[1].split(":", 2);
            out.put(
                    kv[0].strip(),
                    new Price(
                            Double.parseDouble(parts[0]),
                            parts.length > 1 ? Double.parseDouble(parts[1]) : Double.parseDouble(parts[0])));
        }
        return out;
    }

    public double cost(String model, boolean local, int promptTokens, int completionTokens) {
        if (local) return 0;
        var price = prices.getOrDefault(model, UNKNOWN_HOSTED);
        return (promptTokens * price.promptPerMillion() + completionTokens * price.completionPerMillion())
                / 1_000_000.0;
    }

    public String month() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    public Usage used(String tenantId) {
        return usage.get(tenantId, month());
    }

    /** Hard cap: a non-local call is refused once the month's spend reaches the budget. */
    public void ensureBudget(String tenantId, double budgetInr) {
        if (used(tenantId).costInr() >= budgetInr)
            throw new BudgetExhausted("The monthly AI budget is used up; hosted models are paused until it is raised");
    }

    /** Records usage; returns an alert when this call moved spend past 80 percent of the budget. */
    public BudgetAlert charge(
            String tenantId,
            AiTask task,
            double budgetInr,
            int promptTokens,
            int completionTokens,
            double costInr,
            long computeMs) {
        var after = usage.add(tenantId, month(), task, promptTokens, completionTokens, costInr, computeMs);
        double soft = budgetInr * 0.8;
        if (budgetInr > 0 && costInr > 0 && after.costInr() - costInr < soft && after.costInr() >= soft)
            return new BudgetAlert(
                    month(), after.costInr(), budgetInr, (int) Math.floor(after.costInr() / budgetInr * 100));
        return null;
    }
}
