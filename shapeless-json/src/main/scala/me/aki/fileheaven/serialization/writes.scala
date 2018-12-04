package me.aki.fileheaven.serialization

import play.api.libs.json._
import shapeless._
import shapeless.labelled._

/** This type can be summoned for case classes or sealed traits */
case class ImplicitGenericWrite[A](w: OWrites[A])
object ImplicitGenericWrite {
  implicit def adtWrite[A, Repr <: Coproduct](
    implicit
    generic: LabelledGeneric.Aux[A, Repr],
    aw: Lazy[AdtWrite[Repr]]
  ): ImplicitGenericWrite[A] = ImplicitGenericWrite {
    OWrites { a =>
      aw.value.w.writes(generic.to(a))
    }
  }

  implicit def caseWrite[A, Repr <: HList](
    implicit
    generic: LabelledGeneric.Aux[A, Repr],
    cw: Lazy[CaseWrite[Repr]]
  ): ImplicitGenericWrite[A] = ImplicitGenericWrite {
    OWrites { a =>
      cw.value.w.writes(generic.to(a))
    }
  }
}

/** This type can be summoned if a play json [[OWrites]] or a [[ImplicitGenericWrite]] is available */
case class ImplicitWrite[A](w: Writes[A])
object ImplicitWrite {
  implicit def default[A](implicit w: Writes[A]) = ImplicitWrite(w)
  implicit def generic[A](implicit w: ImplicitGenericWrite[A]) = ImplicitWrite(w.w)
}

case class AdtWrite[C <: Coproduct](w: OWrites[C])
object AdtWrite {
  implicit def cNilWrites: AdtWrite[CNil] = AdtWrite(OWrites(_.impossible))

  implicit def coproductWrites[K <: Symbol, H, T <: Coproduct](
    implicit
    witness: Witness.Aux[K],
    hFormat: Lazy[ImplicitGenericWrite[H]],
    tFormat: AdtWrite[T]
  ): AdtWrite[FieldType[K, H] :+: T] = AdtWrite(OWrites {
    case Inl(h) => JsObject(Seq(witness.value.name -> hFormat.value.w.writes(h)))
    case Inr(t) => tFormat.w.writes(t)
  })
}

case class CaseWrite[L <: HList](w: OWrites[L])
object CaseWrite extends LowPriorityWrite {
  implicit def hNilWrite: CaseWrite[HNil] = CaseWrite[HNil](_ => JsObject(Nil))

  implicit def defaultOptionConsFormat[K <: Symbol, H, T <: HList, DH <: Option[Option[H]], DT <: HList](
    implicit
    hWrites: Lazy[ImplicitWrite[H]],
    tFormat: CaseWrite[T],
    witness: Witness.Aux[K]
  ): CaseWrite[FieldType[K, Option[H]] :: T] = CaseWrite[FieldType[K, Option[H]] :: T](OWrites {
      case h :: t =>
        (tFormat.w.writes(t) /: h)((tail, head) =>
          tail + (witness.value.name -> hWrites.value.w.writes(head)))
  })
}
trait LowPriorityWrite {
  implicit def defaultWrite[K <: Symbol, H, T <: HList](
    implicit
    hWrites: Lazy[ImplicitWrite[H]],
    tWrites: CaseWrite[T],
    witness: Witness.Aux[K]
  ): CaseWrite[FieldType[K, H] :: T] = CaseWrite[FieldType[K, H] :: T] {
    OWrites { case h :: t =>
      tWrites.w.writes(t) + (witness.value.name -> hWrites.value.w.writes(h))
    }
  }
}