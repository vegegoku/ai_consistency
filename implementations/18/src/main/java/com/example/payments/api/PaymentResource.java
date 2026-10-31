package com.example.payments.api;
import com.example.payments.application.*;
import com.example.payments.domain.*;
import com.example.payments.security.Caller;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@Path("/api/v1")
public class PaymentResource {
    @Inject MessageParser parser; @Inject PaymentService payments; @Inject ReconciliationService reconciliation; @Inject Caller caller; @Inject ResponseCodec codec;
    @ConfigProperty(name="payments.maximum") String maximum;
    @ConfigProperty(name="payments.future-seconds") long future;
    @ConfigProperty(name="payments.old-days") long old;
    @ConfigProperty(name="payments.body-limit") int bodyLimit;
    @POST @Path("/payments") @Consumes("*/*") @Produces("*/*")
    public Response submit(InputStream body,@Context HttpHeaders headers)throws IOException {
        String output=codec.negotiate(headers.getAcceptableMediaTypes());
        MediaType type=headers.getMediaType();String input=type==null?"":type.getType()+"/"+type.getSubtype();
        if(!Set.of("application/json","application/xml").contains(input))throw new PaymentException(415,"UNSUPPORTED_MEDIA_TYPE");
        if(input.equals("application/json")&&type.getParameters().containsKey("charset")&&!type.getParameters().get("charset").equalsIgnoreCase("utf-8"))throw new PaymentException(415,"UNSUPPORTED_MEDIA_TYPE");
        PaymentCommand command=parser.parse(body.readNBytes(bodyLimit+1),input,Instant.now(),maximum,future,old,bodyLimit);
        TransferResult result=payments.transfer(command,caller.principal,caller.correlationId);
        var p=result.payment();
        if(p.rejectionCode()!=null){
            int status=p.rejectionCode().equals("FORBIDDEN")?403:409;
            return codec.response(status,ErrorMapper.error(p.paymentId(),"REJECTED",p.rejectionCode(),List.of(),p.correlationId(),p.processedAt()),output,"error").build();
        }
        return codec.response(result.created()?201:200,codec.receipt(p),output,"receipt").location(URI.create("/api/v1/payments/"+p.paymentId())).build();
    }
    @GET @Path("/payments/{id}") @Produces("*/*")
    public Response find(@PathParam("id")String id,@Context HttpHeaders headers){
        String output=codec.negotiate(headers.getAcceptableMediaTypes());UUID uuid;try{uuid=UUID.fromString(id);}catch(Exception e){throw new PaymentException(404,"NOT_FOUND");}
        return codec.response(200,codec.receipt(payments.find(uuid,caller.principal)),output,"receipt").build();
    }
    @GET @Path("/accounts/{id}/balance") @Produces("*/*")
    public Response balance(@PathParam("id")String id,@Context HttpHeaders headers){return codec.response(200,payments.balance(id,caller.principal),codec.negotiate(headers.getAcceptableMediaTypes()),"balance").build();}
    @GET @Path("/admin/reconciliation") @Produces("*/*")
    public Response report(@Context HttpHeaders headers){
        var r=reconciliation.report();return codec.response(200,Map.of("generatedAt",r.generatedAt().toString(),"clean",r.clean(),"invalidPayments",r.invalidPayments(),"balanceMismatches",r.balanceMismatches()),codec.negotiate(headers.getAcceptableMediaTypes()),"reconciliation").build();
    }
}
