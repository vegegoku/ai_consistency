package com.example.payments.application;
/** Infrastructure failure seam; the production implementation performs no side effects. */
@FunctionalInterface
public interface TransferFault {void afterDebit();}
