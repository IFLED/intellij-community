/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.htmlInspections;

import com.intellij.codeInsight.daemon.XmlErrorMessages;
import com.intellij.codeInsight.daemon.impl.analysis.XmlHighlightingAwareElementDescriptor;
import com.intellij.codeInspection.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.psi.html.HtmlTag;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.xml.XmlAttributeDescriptor;
import com.intellij.xml.XmlBundle;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.XmlExtension;
import com.intellij.xml.impl.schema.AnyXmlElementDescriptor;
import com.intellij.xml.util.HtmlUtil;
import com.intellij.xml.util.XmlTagUtil;
import com.intellij.xml.util.XmlUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import static com.intellij.codeInsight.daemon.impl.analysis.XmlHighlightVisitor.isInjectedWithoutValidation;

public class RequiredAttributesInspectionBase extends HtmlLocalInspectionTool implements XmlEntitiesInspection {
  @NonNls public static final Key<InspectionProfileEntry> SHORT_NAME_KEY = Key.create(REQUIRED_ATTRIBUTES_SHORT_NAME);
  protected static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.htmlInspections.RequiredAttributesInspection");
  public String myAdditionalRequiredHtmlAttributes = "";

  private static String appendName(String toAppend, String text) {
    if (!toAppend.isEmpty()) {
      toAppend += "," + text;
    }
    else {
      toAppend = text;
    }
    return toAppend;
  }

  @Override
  @NotNull
  public String getGroupDisplayName() {
    return XmlInspectionGroupNames.HTML_INSPECTIONS;
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionsBundle.message("inspection.required.attributes.display.name");
  }

  @Override
  @NotNull
  @NonNls
  public String getShortName() {
    return REQUIRED_ATTRIBUTES_SHORT_NAME;
  }

  @Override
  public String getAdditionalEntries() {
    return myAdditionalRequiredHtmlAttributes;
  }

  @Override
  public void addEntry(String text) {
    myAdditionalRequiredHtmlAttributes = appendName(getAdditionalEntries(), text);
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  public static LocalQuickFix getIntentionAction(String name) {
    return new AddHtmlTagOrAttributeToCustomsIntention(SHORT_NAME_KEY, name, XmlBundle.message("add.optional.html.attribute", name));
  }

  @Override
  protected void checkTag(@NotNull XmlTag tag, @NotNull ProblemsHolder holder, boolean isOnTheFly) {

    String name = tag.getName();
    XmlElementDescriptor elementDescriptor = XmlUtil.getDescriptorFromContext(tag);
    if (elementDescriptor instanceof AnyXmlElementDescriptor || elementDescriptor == null) {
      elementDescriptor = tag.getDescriptor();
    }

    if (elementDescriptor == null) return;
    if ((elementDescriptor instanceof XmlHighlightingAwareElementDescriptor) &&
        !((XmlHighlightingAwareElementDescriptor)elementDescriptor).shouldCheckRequiredAttributes()) {
      return;
    }

    XmlAttributeDescriptor[] attributeDescriptors = elementDescriptor.getAttributesDescriptors(tag);
    Set<String> requiredAttributes = null;

    for (XmlAttributeDescriptor attribute : attributeDescriptors) {
      if (attribute != null && attribute.isRequired()) {
        if (requiredAttributes == null) {
          requiredAttributes = new HashSet<>();
        }
        requiredAttributes.add(attribute.getName(tag));
      }
    }

    if (requiredAttributes != null) {
      for (final String attrName : requiredAttributes) {
        if (!hasAttribute(tag, attrName) &&
            !XmlExtension.getExtension(tag.getContainingFile()).isRequiredAttributeImplicitlyPresent(tag, attrName)) {

          LocalQuickFix insertRequiredAttributeIntention = XmlQuickFixFactory.getInstance().insertRequiredAttributeFix(tag, attrName);
          final String localizedMessage = XmlErrorMessages.message("element.doesnt.have.required.attribute", name, attrName);
          reportOneTagProblem(
            tag,
            attrName,
            localizedMessage,
            insertRequiredAttributeIntention,
            holder,
            getIntentionAction(attrName)
          );
        }
      }
    }
  }

  private static boolean hasAttribute(XmlTag tag, String attrName) {
    final XmlAttribute attribute = tag.getAttribute(attrName);
    if (attribute == null) return false;
    if (attribute.getValueElement() != null) return true;
    if (!(tag instanceof HtmlTag)) return false;
    final XmlAttributeDescriptor descriptor = attribute.getDescriptor();
    return descriptor != null && HtmlUtil.isBooleanAttribute(descriptor, tag);
  }

  private void reportOneTagProblem(final XmlTag tag,
                                   final String name,
                                   @NotNull String localizedMessage,
                                   final LocalQuickFix basicIntention,
                                   ProblemsHolder holder,
                                   final LocalQuickFix addAttributeFix) {
    boolean htmlTag = false;

    if (tag instanceof HtmlTag) {
      htmlTag = true;
      if(isAdditionallyDeclared(getAdditionalEntries(), name)) return;
    }

    if (htmlTag) {
      addElementsForTag(
        tag,
        localizedMessage,
        isInjectedWithoutValidation(tag) ? ProblemHighlightType.INFORMATION : ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
        holder,
        addAttributeFix,
        basicIntention);
    }
    else {
      addElementsForTag(
        tag,
        localizedMessage,
        ProblemHighlightType.ERROR,
        holder,
        basicIntention
      );
    }
  }

  private static void addElementsForTag(XmlTag tag,
                                        String message,
                                        ProblemHighlightType error,
                                        ProblemsHolder holder,
                                        LocalQuickFix... fixes) {
    registerProblem(message, error, holder, XmlTagUtil.getStartTagNameElement(tag), fixes);
    registerProblem(message, error, holder, XmlTagUtil.getEndTagNameElement(tag), fixes);
  }

  private static void registerProblem(String message,
                                      ProblemHighlightType error,
                                      ProblemsHolder holder,
                                      XmlToken start,
                                      LocalQuickFix[] fixes) {
    if (start != null) {
      holder.registerProblem(start, message, error, fixes);
    }
  }

  private static boolean isAdditionallyDeclared(final String additional, String name) {
    name = name.toLowerCase();
    if (!additional.contains(name)) return false;

    StringTokenizer tokenizer = new StringTokenizer(additional, ", ");
    while (tokenizer.hasMoreTokens()) {
      if (name.equals(tokenizer.nextToken())) {
        return true;
      }
    }

    return false;
  }
}
