package com.example.payments.application;
import org.springframework.context.annotation.*;
@Configuration
public class InfrastructureConfiguration {
    @Bean TransferFault transferFault(){return ()->{};}
}
