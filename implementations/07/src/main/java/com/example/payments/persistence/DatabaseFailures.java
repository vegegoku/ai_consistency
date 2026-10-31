package com.example.payments.persistence;
import java.sql.SQLException;
import java.util.Set;
import org.springframework.dao.*;
import org.springframework.transaction.CannotCreateTransactionException;
public final class DatabaseFailures {
    private DatabaseFailures(){}
    public static String sqlState(Throwable failure){
        for(Throwable current=failure;current!=null;current=current.getCause())if(current instanceof SQLException sql && sql.getSQLState()!=null)return sql.getSQLState();return "";
    }
    public static boolean retryable(Throwable failure){
        String state=sqlState(failure);
        return failure instanceof TransientDataAccessException || failure instanceof DataAccessResourceFailureException || failure instanceof CannotCreateTransactionException || state.startsWith("08") || Set.of("55P03","40P01","40001","57014").contains(state);
    }
}
