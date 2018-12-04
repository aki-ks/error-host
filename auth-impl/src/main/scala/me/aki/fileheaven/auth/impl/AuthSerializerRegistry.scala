package me.aki.fileheaven.auth.impl

import com.lightbend.lagom.scaladsl.playjson.JsonSerializerRegistry

object AuthSerializerRegistry extends JsonSerializerRegistry with AuthEntityFormats {
  val serializers = authEntityFormats
}
