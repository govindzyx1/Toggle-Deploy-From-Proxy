package com.google.apigee.edgecallouts.azureeventhubs;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.IOIntensive;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;
import com.google.apigee.edgecallouts.CalloutBase;
import com.google.apigee.encoding.Base16;
import com.google.apigee.util.TimeResolver;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@IOIntensive
public class SasCallout extends CalloutBase implements Execution {
  private static final String varnamePrefix = "sas.";
  private static final String hmacAlgorithm = "HmacSHA256";
  private static final Base64.Encoder base64Encoder = Base64.getEncoder();

  enum EncodingType {
    NONE,
    BASE64,
    BASE64URL,
    BASE16,
    HEX
  };

  public SasCallout(Map properties) {
    super(properties);
  }

  public String getVarnamePrefix() {
    return varnamePrefix;
  }

  private byte[] getKey(MessageContext msgCtxt) throws Exception {
      byte[] keybytes = getByteArrayProperty(msgCtxt, "key");
    if (keybytes==null)
      throw new IllegalStateException("key resolves to null or empty.");

    return keybytes;
  }

  private String getKeyName(MessageContext msgCtxt) throws Exception {
    return (String) getSimpleRequiredProperty("key-name", msgCtxt);
  }

  private String getResourceUri(MessageContext msgCtxt) throws Exception {
    return (String) getSimpleRequiredProperty("resource-uri", msgCtxt);
  }

  /* seconds since epoch of expiry */
  private int getExpiry(MessageContext msgCtxt) throws Exception {
    String lifetimeString = (String) getSimpleRequiredProperty("expiry", msgCtxt);
    Long durationInMilliseconds = TimeResolver.resolveExpression(lifetimeString);
    if (durationInMilliseconds < 0L) throw new IllegalStateException("invalid expiry.");
    int tokenLifetime = ((Long) (durationInMilliseconds / 1000L)).intValue();

    String explicitReferenceTime = (String) getSimpleOptionalProperty("reference-time", msgCtxt);
    Instant referenceTime = Instant.now();

    if (explicitReferenceTime != null) {
      int seconds = Integer.parseInt(explicitReferenceTime);
      if (seconds < 0)throw new IllegalStateException("invalid expiry.");
      referenceTime = Instant.ofEpochSecond(seconds );
    }
    Instant expiry = referenceTime.plus(tokenLifetime, ChronoUnit.SECONDS);
    int expiryEpochSecond = (int) (expiry.getEpochSecond());
    msgCtxt.setVariable(varName("expiry-epoch-second"), Integer.toString(expiryEpochSecond));
    return expiryEpochSecond;
  }

  private byte[] decodeString(String s, EncodingType decodingKind) throws Exception {
    if (decodingKind == EncodingType.HEX || decodingKind == EncodingType.BASE16) {
      return Base16.decode(s);
    }
    if (decodingKind == EncodingType.BASE64) {
      return Base64.getDecoder().decode(s);
    }
    if (decodingKind == EncodingType.BASE64URL) {
      return Base64.getUrlDecoder().decode(s);
    }
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private String getStringProp(MessageContext msgCtxt, String name, String defaultValue)
      throws Exception {
    String value = this.properties.get(name);
    if (value != null) value = value.trim();
    if (value == null || value.equals("")) {
      return defaultValue;
    }
    value = resolveVariableReferences(value, msgCtxt);
    if (value == null || value.equals("")) {
      throw new IllegalStateException(name + " resolves to null or empty.");
    }
    return value;
  }

  private EncodingType getEncodingTypeProperty(MessageContext msgCtxt, String propName)
      throws Exception {
    return EncodingType.valueOf(getStringProp(msgCtxt, propName, "NONE").toUpperCase());
  }

  private byte[] getByteArrayProperty(MessageContext msgCtxt, String propName) throws Exception {
    String thing = this.properties.get(propName);
    if (thing != null) thing = thing.trim();
    if (thing == null || thing.equals("")) {
      return null;
    }
    thing = resolveVariableReferences(thing, msgCtxt);
    if (thing == null || thing.equals("")) {
      throw new IllegalStateException(propName + " resolves to null or empty.");
    }
    EncodingType decodingKind = getEncodingTypeProperty(msgCtxt, propName + "-encoding");
    byte[] a = decodeString(thing, decodingKind);
    return a;
  }

  private static String getStackTraceAsString(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    return sw.toString();
  }

  public ExecutionResult execute(MessageContext msgCtxt, ExecutionContext exeCtxt) {
    try {
      boolean debug = getDebug();
      clearVariables(msgCtxt);
      msgCtxt.removeVariable(varName("token"));
      byte[] keyBytes = getKey(msgCtxt);
      if (debug) {
          msgCtxt.setVariable(varName("key.b16"), Base16.encode(keyBytes));
          msgCtxt.setVariable(varName("key.b64"), base64Encoder.encodeToString(keyBytes));
      }
      String keyName = getKeyName(msgCtxt);
      int expiry = getExpiry(msgCtxt);
      String resourceUri = getResourceUri(msgCtxt);
      Mac hmac = Mac.getInstance(hmacAlgorithm);
      SecretKeySpec key = new SecretKeySpec(keyBytes, hmacAlgorithm);
      hmac.init(key);
      String stringToSign =
          String.format("%s\n%d", URLEncoder.encode(resourceUri, "UTF-8"), expiry);
      byte[] hmacBytes = hmac.doFinal(stringToSign.getBytes("UTF-8"));

      String hmacB64 = new String(base64Encoder.encode(hmacBytes), "UTF-8");
      // String sigB64Url = sigB64.replaceAll("\\+","-").replaceAll("\\/","_").replaceAll("=","");

      String sasToken =
          String.format(
              "SharedAccessSignature sr=%s&sig=%s&se=%d&skn=%s",
              URLEncoder.encode(resourceUri, "UTF-8"),
              URLEncoder.encode(hmacB64, "UTF-8"),
              expiry,
              keyName);

      msgCtxt.setVariable(varName("string-to-sign"), stringToSign);
      msgCtxt.setVariable(varName("token"), sasToken);
    } catch (Exception e) {
      msgCtxt.setVariable(varName("error"), e.getMessage());
      msgCtxt.setVariable(varName("stacktrace"), getStackTraceAsString(e));
      return ExecutionResult.ABORT;
    }
    return ExecutionResult.SUCCESS;
  }
}
