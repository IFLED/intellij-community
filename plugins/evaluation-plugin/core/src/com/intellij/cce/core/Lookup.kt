package com.intellij.cce.core

data class Lookup(
  val prefix: String,
  val suggestions: List<Suggestion>,
  val latency: Long,
  val popupLatency: Long,
  var features: Features? = null,
  val selectedPosition: Int,
  val isNew: Boolean
) {
  fun clearFeatures() {
    features = null
  }

  companion object {
    fun fromExpectedText(
      expectedText: String,
      text: String,
      suggestions: List<Suggestion>,
      latency: Long,
      popupLatency: Long,
      features: Features? = null,
      isNew: Boolean = false
    ): Lookup {
      val selectedPosition = suggestions.indexOfFirst { it.text == expectedText }
        .let { if (it < 0) -1 else it }

      return Lookup(text, suggestions, latency, popupLatency, features, selectedPosition, isNew)
    }
  }
}
