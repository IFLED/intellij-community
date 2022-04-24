package com.intellij.cce.core

data class Suggestion(
  val text: String,
  val presentationText: String,
  val source: SuggestionSource,
  val createdLatency: Long,
  val resultsetLatency: Long,
  val indicatorLatency: Long,
  val lookupLatency: Long,
  val renderedLatency: Long,
  val contributor: String,
  val kind: SuggestionKind = SuggestionKind.ANY
) {
  fun withSuggestionKind(kind: SuggestionKind): Suggestion {
    return Suggestion(text, presentationText, source, createdLatency, resultsetLatency, indicatorLatency, lookupLatency, renderedLatency, contributor, kind)
  }
}
