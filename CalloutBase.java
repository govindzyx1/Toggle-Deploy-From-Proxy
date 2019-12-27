// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.apigee.edgecallouts;

import com.apigee.flow.message.MessageContext;
import com.google.apigee.util.CalloutUtil;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class CalloutBase {
  protected static final String variableReferencePatternString = "(.*?)\\{([^\\{\\} ]+?)\\}(.*?)";
  protected static final Pattern variableReferencePattern =
      Pattern.compile(variableReferencePatternString);
  private static final Pattern commonErrorPattern = Pattern.compile("^(.+?)[:;] (.+)$");
  protected Map<String, String> properties; // read-only

  public CalloutBase(Map properties) {
    this.properties = CalloutUtil.genericizeMap(properties);
  }

  public abstract String getVarnamePrefix();

  protected String varName(String s) {
    return getVarnamePrefix() + s;
  }

  protected boolean getDebug() {
    String wantDebug = (String) this.properties.get("debug");
    boolean debug = (wantDebug != null) && Boolean.parseBoolean(wantDebug);
    return debug;
  }

  protected String normalizeString(String s) {
    s = s.replaceAll("^ +", "");
    s = s.replaceAll("(\r|\n) +", "\n");
    return s.trim();
  }

  protected void clearVariables(MessageContext msgCtxt) {
    msgCtxt.removeVariable(varName("error"));
    msgCtxt.removeVariable(varName("exception"));
    msgCtxt.removeVariable(varName("stacktrace"));
  }

  protected void setExceptionVariables(Exception exc1, MessageContext msgCtxt) {
    String error = exc1.toString().replaceAll("\n", " ");
    msgCtxt.setVariable(varName("exception"), error);
    Matcher matcher = commonErrorPattern.matcher(error);
    if (matcher.matches()) {
      msgCtxt.setVariable(varName("error"), matcher.group(2));
    } else {
      msgCtxt.setVariable(varName("error"), error);
    }
  }

  protected String getSimpleRequiredProperty(String propName, MessageContext msgCtxt)
      throws Exception {
    String value = (String) this.properties.get(propName);
    if (value == null) {
      throw new IllegalStateException(
          String.format("configuration error: %s resolves to an empty string", propName));
    }
    value = value.trim();
    if (value.equals("")) {
      throw new IllegalStateException(
          String.format("configuration error: %s resolves to an empty string", propName));
    }
    value = resolveVariableReferences(value, msgCtxt);
    if (value == null || value.equals("")) {
      throw new IllegalStateException(
          String.format("configuration error: %s resolves to an empty string", propName));
    }
    return value;
  }

  protected String getSimpleOptionalProperty(String propName, MessageContext msgCtxt)
      throws Exception {
    Object value = this.properties.get(propName);
    if (value == null) {
      return null;
    }
    String v = (String) value;
    v = v.trim();
    if (v.equals("")) {
      return null;
    }
    v = resolveVariableReferences(v, msgCtxt);
    if (v == null || v.equals("")) {
      return null;
    }
    return v;
  }

  // If the value of a property contains a pair of curlies,
  // eg, {apiproxy.name}, then "resolve" the value by de-referencing
  // the context variable whose name appears between the curlies.
  // If the variable name is not known, then it returns a null.
  protected String resolveVariableReferences(String spec, MessageContext msgCtxt) {
    Matcher matcher = variableReferencePattern.matcher(spec);
    StringBuffer sb = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(sb, "");
      sb.append(matcher.group(1));
      Object v = msgCtxt.getVariable(matcher.group(2));
      if (v != null) {
        sb.append((String) v);
      }
      sb.append(matcher.group(3));
    }
    matcher.appendTail(sb);
    return (sb.length() > 0) ? sb.toString() : null;
  }
}
