package flatcheck.db

import slick.jdbc.SQLiteProfile.api._
import java.sql.Timestamp

object Types {
  type Offer = (Long, String, String, Timestamp)
}

class Offers(tag: Tag) extends Table[Types.Offer](tag, "Offers") {
  def offerId = column[Long]("offerId", O.PrimaryKey, O.AutoInc)
  def site = column[String]("site")
  def link = column[String]("link", O.Length(1024))
  def recTime = column[Timestamp]("recTime")
  def * = (offerId, site, link, recTime)
}

class OfferDetails(tag: Tag) extends Table[(Long, String, String, Timestamp)](tag, "OfferDetails") {
  def offerId = column[Long]("offerId", O.PrimaryKey, O.AutoInc)
  def site = column[String]("site")
  def link = column[String]("link", O.Length(1024))
  def recTime = column[Timestamp]("recTime")
  def * = (offerId, site, link, recTime)
}