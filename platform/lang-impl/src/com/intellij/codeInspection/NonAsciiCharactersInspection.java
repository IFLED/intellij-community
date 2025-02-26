// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.codeInspection;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.lang.Commenter;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageCommenters;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.properties.charset.Native2AsciiCharset;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.ForeignLeafPsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.usages.ChunkExtractor;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.charset.Charset;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class NonAsciiCharactersInspection extends LocalInspectionTool {
  public boolean CHECK_FOR_NOT_ASCII_IDENTIFIER_NAME = true;
  public boolean CHECK_FOR_NOT_ASCII_STRING_LITERAL;
  public boolean CHECK_FOR_NOT_ASCII_COMMENT;
  public boolean CHECK_FOR_NOT_ASCII_IN_ANY_OTHER_WORD;

  public boolean CHECK_FOR_DIFFERENT_LANGUAGES_IN_IDENTIFIER_NAME = true;
  public boolean CHECK_FOR_DIFFERENT_LANGUAGES_IN_STRING;
  public boolean CHECK_FOR_DIFFERENT_LANGUAGES_IN_COMMENTS;
  public boolean CHECK_FOR_DIFFERENT_LANGUAGES_IN_ANY_OTHER_WORD = true;
  public boolean CHECK_FOR_FILES_CONTAINING_BOM;

  @Override
  @Nls
  @NotNull
  public String getGroupDisplayName() {
    return InspectionsBundle.message("group.names.internationalization.issues");
  }

  @Override
  @NonNls
  @NotNull
  public String getShortName() {
    return "NonAsciiCharacters";
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly, @NotNull LocalInspectionToolSession session) {
    PsiFile file = session.getFile();
    if (!isFileWorthIt(file)) return PsiElementVisitor.EMPTY_VISITOR;
    SyntaxHighlighter syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.getFileType(), file.getProject(), file.getVirtualFile());
    return new PsiElementVisitor() {
      @Override
      public void visitElement(@NotNull PsiElement element) {
        if (!(element instanceof LeafPsiElement)
            // optimization: ignore very frequent white space element
            || element instanceof PsiWhiteSpace) {
          return;
        }

        PsiElementKind kind = getKind(element, syntaxHighlighter);
        TextRange valueRange; // the range inside element with the actual contents with quotes/comment prefixes stripped out
        switch (kind) {
          case STRING:
            if (CHECK_FOR_NOT_ASCII_STRING_LITERAL || CHECK_FOR_DIFFERENT_LANGUAGES_IN_STRING) {
              String text = element.getText();
              valueRange = StringUtil.isQuotedString(text) ? new TextRange(1, text.length() - 1) : null;
              if (CHECK_FOR_DIFFERENT_LANGUAGES_IN_STRING) {
                reportMixedLanguages(element, text, holder, valueRange);
              }
              if (CHECK_FOR_NOT_ASCII_STRING_LITERAL) {
                reportNonAsciiRange(element, text, holder, valueRange);
              }
            }
            break;
          case IDENTIFIER:
            if (CHECK_FOR_NOT_ASCII_IDENTIFIER_NAME) {
              reportNonAsciiRange(element, element.getText(), holder, null);
            }
            if (CHECK_FOR_DIFFERENT_LANGUAGES_IN_IDENTIFIER_NAME) {
              reportMixedLanguages(element, element.getText(), holder, null);
            }
            break;
          case COMMENT:
            if (CHECK_FOR_NOT_ASCII_COMMENT || CHECK_FOR_DIFFERENT_LANGUAGES_IN_COMMENTS) {
              String text = element.getText();
              valueRange = getCommentRange(element, text);
              if (CHECK_FOR_NOT_ASCII_COMMENT) {
                reportNonAsciiRange(element, text, holder, valueRange);
              }
              if (CHECK_FOR_DIFFERENT_LANGUAGES_IN_COMMENTS) {
                reportMixedLanguages(element, text, holder, valueRange);
              }
            }
            break;
          case OTHER:
            if (CHECK_FOR_NOT_ASCII_IN_ANY_OTHER_WORD) {
              String text = element.getText();
              iterateWordsInLeafElement(text, range -> reportMixedLanguages(element, text, holder, range));
            }
            if (CHECK_FOR_DIFFERENT_LANGUAGES_IN_ANY_OTHER_WORD) {
              String text = element.getText();
              iterateWordsInLeafElement(text, range -> reportMixedLanguages(element, text, holder, range));
            }
            break;
        }
      }

      @Override
      public void visitFile(@NotNull PsiFile file) {
        super.visitFile(file);
        if (CHECK_FOR_FILES_CONTAINING_BOM) {
          checkBOM(file, holder);
        }
      }
    };
  }

  private static void iterateWordsInLeafElement(@NotNull String text, @NotNull Consumer<? super TextRange> consumer) {
    int start = -1;
    int c;
    for (int i = 0; i <= text.length(); i += Character.charCount(c)) {
      c = i == text.length() ? -1 : text.codePointAt(i);
      boolean isIdentifierPart = Character.isJavaIdentifierPart(c);
      if (isIdentifierPart && start == -1) {
        start = i;
      }
      if (!isIdentifierPart && start != -1) {
        consumer.accept(new TextRange(start, i));
        start = -1;
      }
    }
  }

  // null means natural range
  private static TextRange getCommentRange(@NotNull PsiElement comment, @NotNull String text) {
    Language language = comment.getLanguage();
    Commenter commenter = LanguageCommenters.INSTANCE.forLanguage(language);
    if (commenter == null) {
      return null;
    }
    for (String prefix : commenter.getLineCommentPrefixes()) {
      if (StringUtil.startsWith(text, prefix)) {
        return new TextRange(prefix.length(), text.length());
      }
    }
    String blockCommentPrefix = commenter.getBlockCommentPrefix();
    if (blockCommentPrefix != null && StringUtil.startsWith(text, blockCommentPrefix)) {
      String suffix = commenter.getBlockCommentSuffix();
      int endOffset = text.length() - (suffix != null && StringUtil.endsWith(text, blockCommentPrefix.length(), text.length(), suffix) ? suffix.length() : 0);
      return new TextRange(blockCommentPrefix.length(), endOffset);
    }
    return null;
  }

  private static void checkBOM(@NotNull PsiFile file, @NotNull ProblemsHolder holder) {
    if (file.getViewProvider().getBaseLanguage() != file.getLanguage()) {
      // don't warn multiple times on files which have multiple views like PHP and JSP
      return;
    }
    VirtualFile virtualFile = file.getVirtualFile();
    byte[] bom = virtualFile == null ? null : virtualFile.getBOM();
    if (bom != null) {
      String hex = IntStream.range(0, bom.length)
        .map(i -> bom[i])
        .mapToObj(b -> StringUtil.toUpperCase(Integer.toString(b & 0x00ff, 16)))
        .collect(Collectors.joining());
      Charset charsetFromBOM = CharsetToolkit.guessFromBOM(bom);
      final String signature = charsetFromBOM == null
                               ? ""
                               : CodeInsightBundle.message("non.ascii.chars.inspection.message.charset.signature", charsetFromBOM.displayName());
      holder.registerProblem(file, CodeInsightBundle.message("non.ascii.chars.inspection.message.file.contains.bom", hex, signature));
    }
  }

  // if element is an identifier, return its text (its non-trivial in case of Groovy)
  private static boolean isIdentifier(@NotNull PsiElement element) {
    if (element instanceof ForeignLeafPsiElement) return false;
    PsiElement parent = element.getParent();
    PsiElement identifier;
    if (parent instanceof PsiNameIdentifierOwner &&
        (identifier = ((PsiNameIdentifierOwner)parent).getNameIdentifier()) != null) {
      // Groovy has this twisted PSI where method.getNameIdentifier() is some random light element
      String text = element.getText();
      return identifier == element || text.equals(identifier.getText());
    }
    // or it maybe the reference name
    if (parent instanceof PsiReference) {
      PsiElement refElement = ((PsiReference)parent).getElement();
      return refElement == parent || refElement == element;
    }
    return false;
  }

  private static boolean isFileWorthIt(@NotNull PsiFile file) {
    if (InjectedLanguageManager.getInstance(file.getProject()).isInjectedFragment(file)) return false;
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) return false;
    CharSequence text = file.getViewProvider().getContents();

    Charset charset = LoadTextUtil.extractCharsetFromFileContent(file.getProject(), virtualFile, text);

    // no sense in checking transparently decoded file: all characters there are already safely encoded
    return !(charset instanceof Native2AsciiCharset);
  }

  private static void reportMixedLanguages(@NotNull PsiElement element,
                                           @NotNull String text,
                                           @NotNull ProblemsHolder holder,
                                           @Nullable("null means natural range") TextRange elementRange) {
    Character.UnicodeScript first = null;
    Character.UnicodeScript second = null;
    int i;
    int codePoint = -1;
    int endOffset = elementRange == null ? text.length() : elementRange.getEndOffset();
    int startOffset = elementRange == null ? 0 : elementRange.getStartOffset();
    for (i = startOffset; i < endOffset; i += Character.charCount(codePoint)) {
      codePoint = text.codePointAt(i);
      Character.UnicodeScript currentScript = Character.UnicodeScript.of(codePoint);
      if (ignoreScript(currentScript)) {
        if (i == startOffset) startOffset += Character.charCount(codePoint);
        continue; // ignore '123.(&$'...
      }
      second = currentScript;
      if (first == null) {
        first = second;
      }
      else if (first != second) {
        break;
      }
    }

    if (first == null || first == second) {
      return;
    }
    // found two scripts
    // now [startOffset..i) are of 'first' script
    int j;
    for (j = i + Character.charCount(codePoint); j < endOffset; j += Character.charCount(codePoint)) {
      codePoint = text.codePointAt(j);
      Character.UnicodeScript currentScript = Character.UnicodeScript.of(codePoint);
      if (ignoreScript(currentScript)) continue;
      if (currentScript != second) {
        break;
      }
    }
    // ignore trailing COMMON script characters
    for (; j > i; j -= Character.charCount(codePoint)) {
      codePoint = text.codePointAt(j-Character.charCount(codePoint));
      if (!ignoreScript(Character.UnicodeScript.of(codePoint))) break;
    }
    // now [i..j) are of 'second' script
    // try to report the range which is the least latin
    TextRange toReport;
    if (first == Character.UnicodeScript.LATIN) {
      toReport = new TextRange(i, j);
    }
    else {
      toReport = new TextRange(startOffset, i);
      Character.UnicodeScript t = second;
      second = first;
      first = t;
    }
    holder.registerProblem(element, toReport, CodeInsightBundle.message("non.ascii.chars.inspection.message.symbols.from.different.languages.found", second, first));
  }

  private static boolean ignoreScript(@NotNull Character.UnicodeScript script) {
    return script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED;
  }

  private static void reportNonAsciiRange(@NotNull PsiElement element,
                                          @NotNull String text,
                                          @NotNull ProblemsHolder holder,
                                          @Nullable("null means natural range") TextRange elementRange) {
    int errorCount = 0;
    int start = -1;
    int startOffset = elementRange == null ? 0 : elementRange.getStartOffset();
    int endOffset = elementRange == null ? text.length() : elementRange.getEndOffset();
    for (int i = startOffset; i <= endOffset; i++) {
      char c = i >= endOffset ? 0 : text.charAt(i);
      if (i == endOffset || c < 128) {
        if (start != -1) {
          TextRange range = new TextRange(start, i);
          holder.registerProblem(element, range, CodeInsightBundle.message("non.ascii.chars.inspection.message.non.ascii.characters"));
          start = -1;
          //do not report too many errors
          if (errorCount++ > 200) break;
        }
      }
      else if (start == -1) {
        start = i;
      }
    }
  }

  @NotNull
  @Override
  public JComponent createOptionsPanel() {
    return new NonAsciiCharactersInspectionFormUi(this).getPanel();
  }

  enum PsiElementKind { IDENTIFIER, STRING, COMMENT, OTHER}
  @NotNull
  private static PsiElementKind getKind(@NotNull PsiElement element, SyntaxHighlighter syntaxHighlighter) {
    TextAttributesKey[] keys;
    if (element.getParent() instanceof PsiLiteralValue || ChunkExtractor.isHighlightedAsString(keys = syntaxHighlighter.getTokenHighlights(((LeafPsiElement)element).getElementType()))) {
      return PsiElementKind.STRING;
    }
    if (isIdentifier(element)) {
      return PsiElementKind.IDENTIFIER;
    }
    if (element instanceof PsiComment || ChunkExtractor.isHighlightedAsComment(keys)) {
      return PsiElementKind.COMMENT;
    }
    return PsiElementKind.OTHER;
  }
}
