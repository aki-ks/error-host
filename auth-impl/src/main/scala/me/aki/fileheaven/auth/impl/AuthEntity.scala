package me.aki.fileheaven.auth.impl

import scala.concurrent.duration._
import java.security.SecureRandom
import java.util.{Base64, UUID}

import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity._
import com.lightbend.lagom.scaladsl.playjson.JsonSerializer
import com.lightbend.lagom.scaladsl.pubsub.{PubSubRegistry, TopicId}
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import me.aki.fileheaven.auth.AuthService
import me.aki.fileheaven.serialization.GenericFormat

object AuthEntity {
  val DefaultAlgorithm = "PBKDF2WithHmacSHA512"
  val DefaultIterations = 8192
  val DefaultTokenValidity = 24.hours
  val Random = new SecureRandom()

  sealed trait AuthCommand
  object AuthCommand {
    case class CreateUserCommand(email: String, name: String, password: String) extends AuthCommand with ReplyType[CreateUserResponse]
    sealed trait CreateUserResponse
    object CreateUserResponse {
      case class Success(token: Token) extends CreateUserResponse
      case object IllegalPassword extends CreateUserResponse
      case object AlreadyRegistered extends CreateUserResponse
    }

    case class LoginCommand(name: String, password: String) extends AuthCommand with ReplyType[LoginResponse]
    sealed trait LoginResponse
    object LoginResponse {
      case class Success(token: Token) extends LoginResponse
      case object NotYetRegistered extends LoginResponse
      case object InvalidCredentials extends LoginResponse
    }
  }

  // Events
  sealed trait AuthEvent extends AggregateEvent[AuthEvent] {
    override def aggregateTag = AuthEvent.Tag
  }
  object AuthEvent {
    val Tag = AggregateEventTag.sharded[AuthEvent](20)
    case class UserCreatedEvent(email: String, name: String, password: Password) extends AuthEvent
  }

  case class AuthState(data: Option[UserData])
  case class UserData(uuid: UUID, email: String, name: String, password: Password)
  case class Password(hash: String, salt: String, iterations: Int, algorithm: String)
  case class Password2(pw: Password)
  case class Token(token: String, issued: Long, validity: Long)
}

trait AuthEntityFormats {
  import AuthEntity._
  val authEntityFormats = List(
    JsonSerializer[AuthCommand.CreateUserCommand],
    JsonSerializer[AuthCommand.CreateUserResponse],
    JsonSerializer[AuthCommand.LoginCommand],
    JsonSerializer[AuthCommand.LoginResponse],

    JsonSerializer[AuthEvent.UserCreatedEvent],

    JsonSerializer[AuthState]
  )

  implicit lazy val createUserCommandFormat: GenericFormat[AuthCommand.CreateUserCommand] = GenericFormat[AuthCommand.CreateUserCommand]
  implicit lazy val createUserResponseFormat: GenericFormat[AuthCommand.CreateUserResponse] = GenericFormat[AuthCommand.CreateUserResponse]
  implicit lazy val loginCommandFormat: GenericFormat[AuthCommand.LoginCommand] = GenericFormat[AuthCommand.LoginCommand]
  implicit lazy val loginResponseFormat: GenericFormat[AuthCommand.LoginResponse] = GenericFormat[AuthCommand.LoginResponse]

  implicit lazy val userCreatedEventFormat: GenericFormat[AuthEvent.UserCreatedEvent] = GenericFormat[AuthEvent.UserCreatedEvent]

  implicit lazy val authStateFormat: GenericFormat[AuthState] = GenericFormat[AuthState]
}

class AuthEntity(pubSub: PubSubRegistry, userIndex: UserIndex) extends PersistentEntity {
  import AuthEntity._

  val tokenTopic = pubSub.refFor(TopicId[AuthService.TokenMessage])

  type Command = AuthCommand
  type Event = AuthEvent
  type State = AuthState
  def initialState: AuthState = AuthState(None)

  def behavior = {
    case AuthState(None) => notYetRegisteredBehavior orElse eventHandlers
    case AuthState(Some(_)) => alreadyRegisteredBehavior orElse eventHandlers
  }

  val notYetRegisteredBehavior = Actions()
    .onCommand[AuthCommand.CreateUserCommand, AuthCommand.CreateUserResponse] {
      case (AuthCommand.CreateUserCommand(email, name, password), ctx, _) =>
        if (isPasswordAllowed(password)) {
          val salt = newSalt
          val hashedPassword = hashPassword(password, salt, DefaultIterations, DefaultAlgorithm)
          val event = AuthEvent.UserCreatedEvent(email, name, Password(hashedPassword, base64(salt), DefaultIterations, DefaultAlgorithm))
          ctx.thenPersist(event) { _ =>
            val token = newToken
            publishToken(token)
            ctx.reply(AuthCommand.CreateUserResponse.Success(token))
          }
        } else {
          ctx.reply(AuthCommand.CreateUserResponse.IllegalPassword)
          ctx.done
        }
    }
    .onReadOnlyCommand[AuthCommand.LoginCommand, AuthCommand.LoginResponse] {
      case (AuthCommand.LoginCommand(_, _), ctx, _) => ctx.reply(AuthCommand.LoginResponse.NotYetRegistered)
    }

  val alreadyRegisteredBehavior = Actions()
    .onReadOnlyCommand[AuthCommand.CreateUserCommand, AuthCommand.CreateUserResponse] {
      case (AuthCommand.CreateUserCommand(_, _, _), ctx, _) =>
        ctx.reply(AuthCommand.CreateUserResponse.AlreadyRegistered)
    }
    .onReadOnlyCommand[AuthCommand.LoginCommand, AuthCommand.LoginResponse] {
      case (AuthCommand.LoginCommand(_, password), ctx, AuthState(Some(userData))) =>
        val pw = userData.password
        if (constantTimeCompare(hashPassword(password, base64Decode(pw.salt), pw.iterations, pw.algorithm), pw.hash)) {
          val token = newToken
          publishToken(token)
          ctx.reply(AuthCommand.LoginResponse.Success(token))
        } else {
          ctx.reply(AuthCommand.LoginResponse.InvalidCredentials)
        }
    }

  def uuid = UUID.fromString(entityId)

  val eventHandlers = Actions()
    .onEvent {
      case (AuthEvent.UserCreatedEvent(email, name, password), state) =>
        state.copy(data = Some(UserData(uuid, email, name, password)))
    }

  def publishToken(token: Token): Unit = {
    val apiToken = AuthService.TokenMeta(token.token, token.issued, token.validity)
    tokenTopic.publish(AuthService.TokenMessage.TokenValidated(apiToken, uuid))
  }

  def isPasswordAllowed(password: String): Boolean = password.length >= 8

  def base64(data: Array[Byte]) = Base64.getEncoder.encodeToString(data)
  def base64Decode(data: String) = Base64.getDecoder.decode(data)

  def newToken = Token(base64(randomBytes(64)), System.currentTimeMillis(), DefaultTokenValidity.toMillis)
  def newSalt = randomBytes(256)
  def randomBytes(amount: Int): Array[Byte] = {
    val array = new Array[Byte](amount)
    Random.nextBytes(array)
    array
  }

  def hashPassword(password: String, salt: Array[Byte], iterations: Int, algorithm: String): String = {
    val spec = new PBEKeySpec(password.toCharArray, salt, iterations, 4096)
    val factory = SecretKeyFactory.getInstance(algorithm)
    base64(factory.generateSecret(spec).getEncoded)
  }

  /** Compare two strings in constant time to prevent timing attacks */
  def constantTimeCompare(stringA: String, stringB: String): Boolean =
    (for ((a, b) ← stringA zip stringB) yield a ^ b)
      .foldLeft(stringA.length ^ stringB.length)(_ | _) == 0
}
