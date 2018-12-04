package me.aki.fileheaven.auth.impl

import com.softwaremill.macwire._
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.pubsub.PubSubComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader}
import me.aki.fileheaven.auth.AuthService
import play.api.libs.ws.ahc.AhcWSComponents

abstract class AuthApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with LagomKafkaComponents
    with PubSubComponents
    with AhcWSComponents {
  lazy val userIndex = wire[UserIndex]
  readSide.register[AuthEntity.AuthEvent](wire[UserIndexReadSideProcessor])

  lazy val lagomServer = serverFor[AuthService](wire[AuthServiceImpl])
  lazy val jsonSerializerRegistry = AuthSerializerRegistry
}

class AuthApplicationLoader extends LagomApplicationLoader {
  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new AuthApplication(context) with LagomDevModeComponents

  override def load(context: LagomApplicationContext): LagomApplication =
    new AuthApplication(context) {
      override def serviceLocator: ServiceLocator = ServiceLocator.NoServiceLocator
    }
}