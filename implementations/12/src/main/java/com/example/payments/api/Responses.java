package com.example.payments.api;

import com.example.payments.domain.*;
import com.example.payments.observability.RequestContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.*;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class Responses {
  @Inject RequestContext context;
  private final ObjectMapper json =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
  private final XmlMapper xml =
      XmlMapper.builder().serializationInclusion(JsonInclude.Include.NON_NULL).build();
  private static final List<Variant> VARIANTS =
      Variant.mediaTypes(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE).build();

  public MediaType negotiate(Request request) {
    var variant = request.selectVariant(VARIANTS);
    if (variant == null)
      throw new ApiException(
          406, "NOT_ACCEPTABLE", "Responses support application/json and application/xml.");
    return variant.getMediaType();
  }

  public MediaType errorMedia(Request request) {
    try {
      return negotiate(request);
    } catch (ApiException e) {
      return MediaType.APPLICATION_JSON_TYPE;
    }
  }

  public Response write(int status, Object entity, MediaType media) {
    try {
      String body =
          media.isCompatible(MediaType.APPLICATION_XML_TYPE)
              ? xml.writer().withRootName("response").writeValueAsString(entity)
              : json.writeValueAsString(entity);
      return Response.status(status)
          .type(media)
          .entity(body)
          .header("X-Correlation-ID", context.correlationId())
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("Response serialization failed", e);
    }
  }

  public Response error(ApiException e, MediaType media) {
    var body = new LinkedHashMap<String, Object>();
    if (e.paymentId() != null) body.put("paymentId", e.paymentId().toString());
    body.put("status", e.definitive() ? "REJECTED" : "ERROR");
    body.put("code", e.code());
    body.put("message", e.getMessage());
    body.put("issues", e.issues());
    body.put("correlationId", context.correlationId().toString());
    body.put("timestamp", Instant.now().toString());
    var response = write(e.http(), body, media);
    var builder = Response.fromResponse(response);
    if (e.http() == 503 || e.http() == 429) builder.header("Retry-After", e.http() == 429 ? 60 : 1);
    if (e.http() == 401) builder.header("WWW-Authenticate", "Bearer");
    return builder.build();
  }
}
