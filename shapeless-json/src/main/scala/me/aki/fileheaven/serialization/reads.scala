package me.aki.fileheaven.serialization

import play.api.libs.json._
import shapeless.labelled._
import shapeless._

/** This type can be summoned for case classes or sealed traits */
case class ImplicitGenericRead[A](r: Reads[A])
object ImplicitGenericRead {
  implicit def adtRead[A, Repr <: Coproduct](
    implicit
    generic: LabelledGeneric.Aux[A, Repr],
    cf: Lazy[AdtRead[Repr]]
  ): ImplicitGenericRead[A] = ImplicitGenericRead {
    cf.value.r.map(generic.from)
  }

  implicit def caseFormat[A, Repr <: HList, Defaults <: HList](
    implicit
    generic: LabelledGeneric.Aux[A, Repr],
    default: Default.Aux[A, Defaults],
    cf: Lazy[CaseRead[Repr, Defaults]]
  ): ImplicitGenericRead[A] = ImplicitGenericRead {
    cf.value.r(default()).map(generic.from)
  }
}

/** This type can be summoned if a play json [[Reads]] or a [[ImplicitGenericRead]] is available. */
case class ImplicitRead[A](r: Reads[A])
object ImplicitRead {
  implicit def generic[A](implicit r: ImplicitGenericRead[A]): ImplicitRead[A] = ImplicitRead(r.r)
  implicit def default[A](implicit r: Reads[A]): ImplicitRead[A] = ImplicitRead(r)
}

case class AdtRead[C <: Coproduct](r: Reads[C])
object AdtRead {
  implicit def cNilRead: AdtRead[CNil] = AdtRead(Reads(_ => JsError("CNil")))

  implicit def coproductReads[K <: Symbol, H, T <: Coproduct](
    implicit
    witness: Witness.Aux[K],
    hFormat: Lazy[ImplicitGenericRead[H]],
    tFormat: Lazy[AdtRead[T]]
  ): AdtRead[FieldType[K, H] :+: T] = AdtRead(Reads { json =>
    json \ witness.value.name match {
      case JsDefined(value) => for (h ← hFormat.value.r.reads(value)) yield Inl(field[K](h))
      case JsUndefined() => for (t ← tFormat.value.r.reads(json)) yield Inr(t)
    }
  })
}

case class CaseRead[L <: HList, DL <: HList](r: DL => Reads[L])
object CaseRead extends LowPriorityRead {
  implicit def hNilRead: CaseRead[HNil, HNil] = CaseRead[HNil, HNil] {
    case HNil => Reads(_ => JsSuccess(HNil))
  }

  implicit def defaultOptionConsFormat[K <: Symbol, H, T <: HList, DH <: Option[Option[H]], DT <: HList](
    implicit
    hReads: Lazy[ImplicitRead[H]],
    tFormat: CaseRead[T, DT],
    witness: Witness.Aux[K]
  ): CaseRead[FieldType[K, Option[H]] :: T, DH :: DT] = CaseRead {
    case dhOption :: dt => Reads { obj =>
      for {
        headOpt ← obj \ witness.value.name match {
          case JsDefined(value) => hReads.value.r.reads(value).map(Some[H])
          case JsUndefined() => JsSuccess(dhOption.flatten)
        }
        tail ← tFormat.r(dt).reads(obj)
      } yield field[K](headOpt) :: tail
    }
  }
}
trait LowPriorityRead {
  implicit def defaultRead[K <: Symbol, H, T <: HList, DH <: Option[H], DT <: HList](
    implicit
    hReads: Lazy[ImplicitRead[H]],
    tFormat: CaseRead[T, DT],
    witness: Witness.Aux[K]
  ): CaseRead[FieldType[K, H] :: T, DH :: DT] = CaseRead {
    case dhOption :: dt => Reads { obj =>
      val fieldName = witness.value.name
      for {
        headOpt ← (obj \ fieldName).validateOpt[H](hReads.value.r)
        head ← headOpt.orElse(dhOption).fold[JsResult[H]](JsError(s"No such field: $fieldName"))(dh => JsSuccess(dh))
        tail ← tFormat.r(dt).reads(obj)
      } yield field[K](head) :: tail
    }
  }
}
