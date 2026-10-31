package com.example.payments.api;

import com.example.payments.domain.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.core.*;
import java.util.*;

@RequestScoped
public class ResponseCodec {
  @jakarta.ws.rs.core.Context Request request;
  private static final List<Variant> VARIANTS =
      Variant.mediaTypes(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE).build();
  private final ObjectMapper json = new ObjectMapper();
  private final XmlMapper xml = new XmlMapper();

  public void check() {
    if (request.selectVariant(VARIANTS) == null) throw new ApiException(406, "NOT_ACCEPTABLE");
  }

  public Response response(int status, Object body) {
    Variant v = request.selectVariant(VARIANTS);
    MediaType type = v == null ? MediaType.APPLICATION_JSON_TYPE : v.getMediaType();
    try {
      String encoded =
          type.equals(MediaType.APPLICATION_XML_TYPE)
              ? xml.writer().withRootName("response").writeValueAsString(body)
              : json.writeValueAsString(body);
      Response.ResponseBuilder result = Response.status(status).type(type).entity(encoded);
      if (status == 503 || status == 429) result.header("Retry-After", status == 429 ? "60" : "2");
      return result.build();
    } catch (Exception e) {
      throw new IllegalStateException("Response encoding failed", e);
    }
  }
}
