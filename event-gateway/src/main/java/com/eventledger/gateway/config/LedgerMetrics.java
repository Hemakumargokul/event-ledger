package com.eventledger.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Custom Gateway metrics per SPEC §9.3. */
@Component
public class LedgerMetrics {

    private final MeterRegistry meterRegistry;

    public LedgerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** outcome: created | duplicate | queued | rejected | unavailable */
    public void eventSubmitted(String type, String outcome) {
        meterRegistry.counter("ledger.events.submitted", "type", type, "outcome", outcome)
                .increment();
    }

    /** Times one downstream Account Service call attempt. */
    public <T> T timeAccountCall(Supplier<T> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T result = call.get();
            sample.stop(accountCallTimer("success"));
            return result;
        } catch (RuntimeException e) {
            sample.stop(accountCallTimer("failure"));
            throw e;
        }
    }

    private Timer accountCallTimer(String outcome) {
        return Timer.builder("ledger.account.call")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
