package flatcheck.db

import slick.jdbc.SQLiteProfile.api._
import java.sql.Timestamp

object Types {
  type Offer = (Long, String, String, Timestamp, Timestamp)
  type OfferShortId = (Long, String, String)
  type OfferDetail = (Long,
    Int,
    Int,
    Int,
    String,
    String,
    String,
    String,
    String)
  type OfferWithDetails = (Long,
    String,
    String,
    Timestamp,
    Timestamp,
    Int,
    Int,
    Int,
    String,
    String,
    String,
    String,
    String)
}

class Offers(tag: Tag) extends Table[Types.Offer](tag, "Offers") {
  def offerId = column[Long]("offerId", O.PrimaryKey, O.AutoInc)
  def site = column[String]("site")
  def link = column[String]("link", O.Length(1024))
  def firstSeen = column[Timestamp]("firstSeen")
  def lastSeen = column[Timestamp]("lastSeen")
  def * = (offerId, site, link, firstSeen, lastSeen)
}

class OfferDetails(tag: Tag) extends Table[Types.OfferDetail](tag, "OfferDetails") {
  val offers = TableQuery[Offers]
  def offerId = column[Long]("offerId")
  def offerFK = foreignKey("offer_FK",offerId, offers)(
    _.offerId,
    onUpdate=ForeignKeyAction.Restrict,
    onDelete=ForeignKeyAction.Cascade)
  def priceHUF = column[Int]("priceHUF")
  def sizeSM = column[Int]("sizeSM")
  def roomsNum = column[Int]("roomsNum")
  def address = column[String]("address", O.Length(256))
  def area = column[String]("area", O.Length(128))
  def description = column[String]("description", O.Length(2048))
  def floor = column[String]("floor", O.Length(64))
  def flatCondition = column[String]("flatCondition", O.Length(64))
  def * = (offerId, priceHUF, sizeSM, roomsNum, address, area, description, floor, flatCondition)
}