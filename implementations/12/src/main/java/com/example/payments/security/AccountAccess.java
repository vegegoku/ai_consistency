package com.example.payments.security;

import static com.example.payments.persistence.Sql.*;

import java.sql.*;

public final class AccountAccess {
  private AccountAccess() {}

  public static boolean debit(Connection c, String account, String principal) throws SQLException {
    return exists(
        c,
        "SELECT 1 FROM accounts a JOIN customers cu ON cu.id=a.customer_id WHERE a.id=? AND"
            + " cu.status='ACTIVE' AND (a.customer_id=? OR EXISTS (SELECT 1 FROM"
            + " account_permissions p WHERE p.account_id=a.id AND p.principal=? AND p.can_debit))",
        account,
        principal,
        principal);
  }

  public static boolean view(Connection c, String account, String principal) throws SQLException {
    return exists(
        c,
        "SELECT 1 FROM accounts a JOIN customers cu ON cu.id=a.customer_id WHERE a.id=? AND"
            + " cu.status='ACTIVE' AND (a.customer_id=? OR EXISTS (SELECT 1 FROM"
            + " account_permissions p WHERE p.account_id=a.id AND p.principal=? AND (p.can_view OR"
            + " p.can_debit)))",
        account,
        principal,
        principal);
  }
}
