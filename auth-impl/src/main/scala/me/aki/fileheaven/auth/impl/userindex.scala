package me.aki.fileheaven.auth.impl

import java.util.UUID

import akka.Done
import com.datastax.driver.core.{BoundStatement, PreparedStatement}
import com.lightbend.lagom.scaladsl.persistence.ReadSideProcessor
import com.lightbend.lagom.scaladsl.persistence.cassandra.{CassandraReadSide, CassandraSession}

import scala.concurrent.{ExecutionContext, Future}

object UserIndex {
  val TableName = "userindex"
  val Id = "id"
  val Name = "name"
  val Email = "email"

  def caseIgnored(text: String) = text.trim.toLowerCase

  def createTable(session: CassandraSession)(implicit ec: ExecutionContext) = {
    for {
      Done ← session.executeCreateTable(s"CREATE TABLE IF NOT EXISTS $TableName($Id uuid PRIMARY KEY, $Name text, $Email text);")
      Done ← session.executeCreateTable(s"CREATE INDEX IF NOT EXISTS name_index ON $TableName ($Name);")
      Done ← session.executeCreateTable(s"CREATE INDEX IF NOT EXISTS email_index ON $TableName ($Email);")
    } yield Done
  }
}

/**
  * Access to a table that zips user ids, names and their email
  */
class UserIndex(session: CassandraSession)(implicit ec: ExecutionContext) {
  import UserIndex._

  def prepared[A](future: Future[A]) = future

  private val selectByNameStatement = prepared { session.prepare(s"SELECT * FROM $TableName WHERE $Name=?") }
  private val selectByEmailStatement = prepared { session.prepare(s"SELECT * FROM $TableName WHERE $Email=?") }

  def userByNameOrEmail(nameOrEmail: String)(implicit ec: ExecutionContext): Future[Option[UUID]] = for {
    (nameMatch, emailMatch) ← userByName(nameOrEmail) zip userByEmail(nameOrEmail)
  } yield nameMatch orElse emailMatch

  def userByName(name: String)(implicit ec: ExecutionContext): Future[Option[UUID]] = for {
    selectByName ← selectByNameStatement
    rowOpt ← session.selectOne(selectByName.bind(caseIgnored(name)))
  } yield rowOpt.map(_.getUUID(Id))

  def userByEmail(email: String)(implicit ec: ExecutionContext): Future[Option[UUID]] = for {
    selectByEmail ← selectByEmailStatement
    rowOpt ← session.selectOne(selectByEmail.bind(caseIgnored(email)))
  } yield rowOpt.map(_.getUUID(Id))
}

class UserIndexReadSideProcessor(session: CassandraSession, readSide: CassandraReadSide)(implicit ec: ExecutionContext)
  extends ReadSideProcessor[AuthEntity.AuthEvent] {
  import UserIndex._

  val aggregateTags = AuthEntity.AuthEvent.Tag.allTags

  private var createUserStatement: PreparedStatement = _

  def createTable: Future[Done] = UserIndex.createTable(session)

  def prepareStatements: Future[Done] =
    for (createUser ← session.prepare(s"INSERT INTO $TableName ($Id,$Name,$Email) VALUES (?, ?, ?)")) yield {
      createUserStatement = createUser
      Done
    }

  def createUser(id: UUID, name: String, email: String): Future[List[BoundStatement]] =
    Future.successful(createUserStatement.bind(id, caseIgnored(name), caseIgnored(email)) :: Nil)

  override def buildHandler() = {
    readSide.builder[AuthEntity.AuthEvent]("UserIndexReadSideProcessor")
      .setGlobalPrepare(() => createTable)
      .setPrepare(_ => prepareStatements)
      .setEventHandler[AuthEntity.AuthEvent.UserCreatedEvent](e => createUser(UUID.fromString(e.entityId), e.event.name, e.event.email))
      .build()
  }
}
