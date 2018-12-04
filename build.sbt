ThisBuild / scalaVersion := "2.12.7"

import scala.concurrent.duration._
ThisBuild / lagomCassandraMaxBootWaitingTime := 5.minutes
ThisBuild / lagomCassandraCleanOnStart := true
ThisBuild / lagomKafkaCleanOnStart := true

val macwire   = "com.softwaremill.macwire"  %% "macros"                   % "2.2.5"  % "provided"
val shapeless = "com.chuusai"               %% "shapeless"                % "2.3.3"
val playJson  = "com.typesafe.play"         %% "play-json"                % "2.6.10" % "provided"

lazy val `auth-api` = (project in file("auth-api"))
  .dependsOn(`shapeless-json`)
  .settings(
    version := "1.0-SNAPSHOT",
    libraryDependencies += lagomScaladslApi
  )

lazy val `auth-impl` = (project in file("auth-impl"))
  .enablePlugins(LagomScala)
  .dependsOn(`auth-api`)
  .settings(
    version := "1.0-SNAPSHOT",
    libraryDependencies ++=
      macwire ::
      lagomScaladslPersistenceCassandra ::
      lagomScaladslKafkaBroker ::
      lagomScaladslPubSub :: Nil
  )

lazy val `shapeless-json` = (project in file("shapeless-json"))
  .settings(
    version := "1.0-SNAPSHOT",
    libraryDependencies ++=
      shapeless ::
      playJson :: Nil
  )
