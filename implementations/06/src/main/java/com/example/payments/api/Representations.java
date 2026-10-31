package com.example.payments.api;

import com.example.payments.domain.PaymentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.*;
import java.util.*;

@ApplicationScoped
public class Representations {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final XmlMapper xml = new XmlMapper();

  public Response response(int status, Object body, HttpHeaders headers) {
    String type = negotiate(headers);
    try {
      return Response.status(status)
          .type(type)
          .entity(
              type.equals("application/xml")
                  ? xml.writer().withRootName("response").writeValueAsString(body)
                  : json.writeValueAsString(body))
          .build();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String negotiate(HttpHeaders headers) {
    var list = headers.getAcceptableMediaTypes();
    if (list.isEmpty()) return "application/json";
    for (MediaType t : list) {
      if (new java.math.BigDecimal(t.getParameters().getOrDefault("q", "1")).signum() == 0)
        continue;
      if (t.isCompatible(MediaType.APPLICATION_JSON_TYPE)) return "application/json";
      if (t.isCompatible(MediaType.APPLICATION_XML_TYPE)) return "application/xml";
    }
    throw new PaymentException(406, "NOT_ACCEPTABLE");
  }
}
