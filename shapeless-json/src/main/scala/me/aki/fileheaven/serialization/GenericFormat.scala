package me.aki.fileheaven.serialization

import play.api.libs.json._

case class GenericFormat[A](r: Reads[A], w: OWrites[A]) extends OFormat[A] {
  def reads(json: JsValue): JsResult[A] = r.reads(json)
  def writes(a: A): JsObject = w.writes(a)
}
object GenericFormat {
  def apply[A](implicit r: ImplicitGenericRead[A], w: ImplicitGenericWrite[A]): GenericFormat[A] = GenericFormat(r.r, w.w)
}
