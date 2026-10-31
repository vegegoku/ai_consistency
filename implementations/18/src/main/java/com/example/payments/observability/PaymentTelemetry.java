package com.example.payments.observability;
import com.example.payments.domain.TransferResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class PaymentTelemetry {
    @Inject MeterRegistry metrics;
    private static final Logger LOG=Logger.getLogger(PaymentTelemetry.class);
    public void committed(TransferResult result,long started){
        try {
            var p=result.payment();metrics.counter(result.created()?"payments_outcomes":"payments_replays","code",p.rejectionCode()==null?"COMPLETED":p.rejectionCode()).increment();
            metrics.timer("payments_processing").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
            MDC.put("paymentId",p.paymentId().toString());MDC.put("correlationId",p.correlationId());MDC.put("status",p.status());MDC.put("code",p.rejectionCode()==null?"COMPLETED":p.rejectionCode());
            LOG.infof("Payment outcome committed; replay=%s latencyMs=%d",!result.created(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        } catch(RuntimeException ignored){/* Telemetry must not change an already committed outcome. */}
        finally {MDC.remove("paymentId");MDC.remove("status");MDC.remove("code");}
    }
    public void failure(String code){try{metrics.counter("payments_failures","code",code).increment();}catch(RuntimeException ignored){}}
}
