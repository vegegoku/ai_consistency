package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.ApiFailure;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentCodec codec;private final PaymentService service;private final ResponseWriter writer;private final int bodyLimit;
    public PaymentController(PaymentCodec codec,PaymentService service,ResponseWriter writer,@Value("${payments.max-body-bytes:65536}") int bodyLimit){this.codec=codec;this.service=service;this.writer=writer;this.bodyLimit=bodyLimit;}
    @PostMapping("/payments")
    public ResponseEntity<String> submit(HttpServletRequest request,Authentication auth) throws java.io.IOException {
        String format=writer.format(request.getHeader("Accept"));String content=request.getContentType();String type;
        try{type=MediaType.parseMediaType(content==null?"":content).getType()+"/"+MediaType.parseMediaType(content).getSubtype();}catch(Exception e){throw new ApiFailure(415,"UNSUPPORTED_MEDIA_TYPE");}
        if(!Set.of("application/json","application/xml").contains(type))throw new ApiFailure(415,"UNSUPPORTED_MEDIA_TYPE");
        var command=codec.parseForSubmission(request.getInputStream().readNBytes(bodyLimit+1),type);request.setAttribute("paymentId",command.paymentId().toString());
        var result=service.transfer(command,auth.getName(),(String)request.getAttribute("correlationId"));
        request.setAttribute("errorCode",result.body().getOrDefault("code","COMPLETED"));
        var response=writer.response(result.http(),result.body(),format,result.http()<400?"receipt":"error");
        if(result.http()==201)return ResponseEntity.created(URI.create("/api/v1/payments/"+command.paymentId())).contentType(response.getHeaders().getContentType()).body(response.getBody());
        return response;
    }
    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<String> find(@PathVariable String paymentId,HttpServletRequest request,Authentication auth){
        String format=writer.format(request.getHeader("Accept"));UUID id;try{id=UUID.fromString(paymentId);}catch(Exception e){throw new ApiFailure(404,"NOT_FOUND");}
        request.setAttribute("paymentId",id.toString());var result=service.find(id,auth.getName());return writer.response(200,result.body(),format,"receipt");
    }
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<String> balance(@PathVariable String accountId,HttpServletRequest request,Authentication auth){String format=writer.format(request.getHeader("Accept"));return writer.response(200,service.balance(accountId,auth.getName()),format,"balance");}
    @GetMapping("/operations/reconciliation")
    public ResponseEntity<String> reconciliation(HttpServletRequest request){String format=writer.format(request.getHeader("Accept"));var issues=service.reconcile();return writer.response(200,Map.of("consistent",issues.isEmpty(),"issues",issues),format,"reconciliation");}
}
