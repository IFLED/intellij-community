package com.intellij.cce.workspace

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.intellij.cce.core.Session
import com.intellij.cce.core.Suggestion
import com.intellij.cce.core.TokenProperties
import com.intellij.cce.workspace.info.FileSessionsInfo
import org.apache.commons.lang.StringEscapeUtils
import java.lang.reflect.Type

class SessionSerializer {
  companion object {
    private val gson = GsonBuilder()
      .serializeNulls()
      .registerTypeAdapter(TokenProperties::class.java, TokenProperties.JsonAdapter)
      .create()
    private val gsonForJs = GsonBuilder()
      .serializeNulls()
      .registerTypeAdapter(TokenProperties::class.java, TokenProperties.JsonAdapter)
      .registerTypeAdapter(Suggestion::class.java, object : JsonSerializer<Suggestion> {
        override fun serialize(src: Suggestion, typeOfSrc: Type, context: JsonSerializationContext): JsonObject {
          val jsonObject = JsonObject()
          jsonObject.addProperty("text", escapeHtml(src.text))
          jsonObject.addProperty("presentationText", escapeHtml(src.presentationText))
          jsonObject.addProperty("createdLatency", escapeHtml(src.createdLatency.toString()))
          jsonObject.addProperty("resultsetLatency", escapeHtml(src.resultsetLatency.toString()))
          jsonObject.addProperty("indicatorLatency", escapeHtml(src.indicatorLatency.toString()))
          jsonObject.addProperty("lookupLatency", escapeHtml(src.lookupLatency.toString()))
          jsonObject.addProperty("renderedLatency", escapeHtml(src.renderedLatency.toString()))
          jsonObject.addProperty("contributor", escapeHtml(src.contributor))
          return jsonObject
        }
      })
      .create()

    private fun escapeHtml(value: String) =
      StringEscapeUtils.escapeHtml(value)
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("([\r\n\t])".toRegex(), "")
        .replace("""(\\r|\\n|\\t)""".toRegex(), "")
        .replace("\\", "&#92;")
        .replace("★", "*")
  }

  fun serialize(sessions: FileSessionsInfo): String = gson.toJson(sessions)

  fun serialize(sessions: List<Session>): String {
    val map = HashMap<String, Session>()
    for (session in sessions) {
      map[session.id] = session
    }
    return gsonForJs.toJson(map)
  }

  fun deserialize(json: String): FileSessionsInfo {
    return gson.fromJson(json, FileSessionsInfo::class.java)
  }
}