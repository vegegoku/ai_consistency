package com.example.payments.api;
import com.example.payments.domain.ApiFailure;
import jakarta.servlet.http.*;
import org.springframework.dao.*;
import org.springframework.web.bind.annotation.*;
import io.micrometer.core.instrument.MeterRegistry;
@RestControllerAdvice
public class ApiExceptionHandler {
    private final ResponseWriter writer;private final MeterRegistry metrics;
    public ApiExceptionHandler(ResponseWriter writer,MeterRegistry metrics){this.writer=writer;this.metrics=metrics;}
    @ExceptionHandler(ApiFailure.class) void known(ApiFailure e,HttpServletRequest req,HttpServletResponse res)throws java.io.IOException{if(e.paymentId()!=null)req.setAttribute("paymentId",e.paymentId().toString());writer.writeError(e,req,res);}
    @ExceptionHandler({DataAccessResourceFailureException.class,TransientDataAccessException.class,org.springframework.transaction.CannotCreateTransactionException.class}) void unavailable(Exception e,HttpServletRequest req,HttpServletResponse res)throws java.io.IOException{writer.writeError(new ApiFailure(503,"TEMPORARILY_UNAVAILABLE"),req,res);}
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class) void missing(Exception e,HttpServletRequest req,HttpServletResponse res)throws java.io.IOException{writer.writeError(new ApiFailure(404,"NOT_FOUND"),req,res);}
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) void method(Exception e,HttpServletRequest req,HttpServletResponse res)throws java.io.IOException{writer.writeError(new ApiFailure(405,"METHOD_NOT_ALLOWED"),req,res);}
    @ExceptionHandler(Exception.class) void unexpected(Exception e,HttpServletRequest req,HttpServletResponse res)throws java.io.IOException{metrics.counter("payments.unexpected.failures").increment();writer.writeError(new ApiFailure(500,"INTERNAL_ERROR"),req,res);}
}
