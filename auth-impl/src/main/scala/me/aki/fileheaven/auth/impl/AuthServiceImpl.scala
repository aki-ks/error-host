package me.aki.fileheaven.auth.impl

import java.util.UUID

import akka.stream.Materializer
import com.softwaremill.macwire._
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import com.lightbend.lagom.scaladsl.pubsub.{PubSubRegistry, TopicId}
import me.aki.fileheaven.auth.AuthService

import scala.concurrent.{ExecutionContext, Future}

class AuthServiceImpl(pubSub: PubSubRegistry, persistentEntities: PersistentEntityRegistry, userIndex: UserIndex)
  (implicit ec: ExecutionContext, materializer: Materializer) extends AuthService {
  import AuthEntity._

  lazy val tokenPubSub = pubSub.refFor(TopicId[AuthService.TokenMessage])

  persistentEntities.register(wire[AuthEntity])

  def login = ServiceCall { case AuthService.LoginRequest(login, password) =>
    userIndex.userByNameOrEmail(login) flatMap {
      case None => Future.successful(AuthService.LoginResponse.InvalidCredentials)
      case Some(uuid) =>
        val user = persistentEntities.refFor[AuthEntity](uuid.toString)
        user.ask(AuthCommand.LoginCommand(login, password)) map {
          case AuthCommand.LoginResponse.Success(token) => AuthService.LoginResponse.Success(AuthService.TokenMeta(token.token, token.issued, token.validity))
          case AuthCommand.LoginResponse.InvalidCredentials => AuthService.LoginResponse.InvalidCredentials
          case AuthCommand.LoginResponse.NotYetRegistered => AuthService.LoginResponse.InvalidCredentials
        }
    }
  }

  def register = ServiceCall { case AuthService.RegisterRequest(email, name, password) =>
    def ifNotYetRegistered[A](function: => Future[AuthService.RegisterResponse]): Future[AuthService.RegisterResponse] =
      userIndex.userByName(name) flatMap {
        case Some(_) => Future.successful(AuthService.RegisterResponse.NameTaken)
        case None =>
          userIndex.userByEmail(email) flatMap {
            case Some(_) => Future.successful(AuthService.RegisterResponse.EmailTaken)
            case None => function
          }
      }

    ifNotYetRegistered {
      val newUserId = UUID.randomUUID()
      val user = persistentEntities.refFor[AuthEntity](newUserId.toString)
      user.ask(AuthCommand.CreateUserCommand(email, name, password)) map {
        case AuthCommand.CreateUserResponse.AlreadyRegistered => throw new IllegalStateException("A just generated uuid is already registered")
        case AuthCommand.CreateUserResponse.IllegalPassword => AuthService.RegisterResponse.DisallowedPassword
        case AuthCommand.CreateUserResponse.Success(token) => AuthService.RegisterResponse.Success(AuthService.TokenMeta(token.token, token.issued, token.validity))
      }
    }
  }

  def tokenTopic =
    TopicProducer.singleStreamWithOffset { offset =>
      for (event ← tokenPubSub.subscriber) yield (event, offset)
    }
}
