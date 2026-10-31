package com.example.payments.api;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.InputStream;
import java.net.URI;

@Path("/api/v1") @Blocking
public class PaymentResource {
    @Inject PaymentService service;
    @Inject MessageCodec codec;
    @Inject Responses responses;
    @Inject ReconciliationService reconciliation;
    @Context HttpHeaders headers;
    @Context ContainerRequestContext request;
    String principal(){String caller=(String)request.getProperty("principal");if(caller==null)throw new ApiProblem(401,"UNAUTHENTICATED");return caller;}
    @POST @Path("/payments") @Consumes("*/*") @Produces("*/*")
    public Response submit(InputStream body) {
        responses.xml(headers); // Validate negotiation before any financial side effect.
        MediaType type=headers.getMediaType();
        String media=type==null?"":type.getType()+"/"+type.getSubtype();
        var p=codec.parse(codec.readBody(body),media);
        request.setProperty("paymentId",p.paymentId());
        var outcome=service.transfer(p,principal(),(String)request.getProperty("correlationId"));
        var record=outcome.payment();
        if(record.status().equals("REJECTED"))throw record.rejection();
        var result=responses.send(outcome.replay()?200:201,record.receipt(),headers);
        return Response.fromResponse(result).location(URI.create("/api/v1/payments/"+record.paymentId())).build();
    }
    @GET @Path("/payments/{paymentId}") @Produces("*/*")
    public Response get(@PathParam("paymentId") String id) {
        var p=service.get(id,principal());request.setProperty("paymentId",p.paymentId());
        return responses.send(200,p.status().equals("COMPLETED")?p.receipt():responses.error(p.rejection(),p.correlationId()),headers);
    }
    @GET @Path("/accounts/{accountId}/balance") @Produces("*/*")
    public Response balance(@PathParam("accountId") String id){return responses.send(200,service.balance(id,principal()),headers);}
    @GET @Path("/admin/reconciliation") @Produces("*/*")
    public Response reconcile(){principal();return responses.send(200,reconciliation.report(),headers);}
}
