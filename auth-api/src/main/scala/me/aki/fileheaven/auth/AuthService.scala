package me.aki.fileheaven
package auth

import java.util.UUID

import com.lightbend.lagom.scaladsl.api._
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.broker.kafka.{KafkaProperties, PartitionKeyStrategy}
import me.aki.fileheaven.serialization.GenericFormat

object AuthService {
  case class LoginRequest(name: String, password: String)

  sealed trait LoginResponse
  object LoginResponse {
    case class Success(token: TokenMeta) extends LoginResponse
    case object InvalidCredentials extends LoginResponse
  }

  case class RegisterRequest(email: String, name: String, password: String)

  sealed trait RegisterResponse
  object RegisterResponse {
    case class Success(token: TokenMeta) extends RegisterResponse
    case object DisallowedPassword extends RegisterResponse
    case object NameTaken extends RegisterResponse
    case object EmailTaken extends RegisterResponse
  }

  case class TokenMeta(token: String, issued: Long, validity: Long)

  sealed trait TokenMessage { def id: String }
  object TokenMessage {
    case class TokenValidated(token: TokenMeta, user: UUID) extends TokenMessage { def id = token.token }
    case class TokenInvalidated(token: String) extends TokenMessage { def id = token }
  }
}

trait AuthServiceFormats {
  import AuthService._
  implicit lazy val loginRequestFormat: GenericFormat[LoginRequest] = GenericFormat[LoginRequest]
  implicit lazy val loginResponseFormat: GenericFormat[LoginResponse] = GenericFormat[LoginResponse]

  implicit lazy val registerRequestFormat: GenericFormat[RegisterRequest] = GenericFormat[RegisterRequest]
  implicit lazy val registerResponseFormat: GenericFormat[RegisterResponse] = GenericFormat[RegisterResponse]

  implicit lazy val tokenMessageFormat: GenericFormat[TokenMessage] = GenericFormat[TokenMessage]
}

trait AuthService extends Service with AuthServiceFormats {
  import AuthService._

  /**
    * Login as a with username/email and password to retrieve a token.
    * The token can be used to authenticate at any microservice.
    */
  def login: ServiceCall[LoginRequest, LoginResponse]

  /**
    * Register a new account
    */
  def register: ServiceCall[RegisterRequest, RegisterResponse]

  /**
    * Api for subscribing to all tokens that were validated or invalidated.
    */
  def tokenTopic: Topic[TokenMessage]

  def descriptor = {
    import Service._
    named("auth")
      .withCalls(
        pathCall("/api/auth/login", login),
        pathCall("/api/auth/register", register),
      )
      .withTopics(
        topic("token-topic", tokenTopic)
          .addProperty(KafkaProperties.partitionKeyStrategy, PartitionKeyStrategy[TokenMessage](_.id))
      )
      .withAutoAcl(true)
  }
}
