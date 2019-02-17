package flatcheck.db

import com.typesafe.scalalogging.LazyLogging
import flatcheck.db.Types.Offer
import slick.jdbc.SQLiteProfile.api._
import slick.lifted.{Query, TableQuery}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

trait OffersDS {
  def containsOfferWithLink(offerLink: String) : Boolean
  def addOffer(offer: Offer) : Unit
}


class OffersSQLiteDataSource(val db: Database, val timeOutMins: Long = 5) extends OffersDS with LazyLogging  {
  val offers = TableQuery[Offers]

  logger.debug("Initializing offers table if needed...")
  Await.result(db.run(DBIO.seq(offers.schema.createIfNotExists)), Duration(timeOutMins, "min"))

  override def containsOfferWithLink(offerLink: String): Boolean = {
    val filterQuery: Query[Offers, Offer, Seq] = offers.filter(_.link === offerLink)
    val res : Seq[Offer] = Await.result(db.run(filterQuery.result), Duration(timeOutMins, "min"))
    logger.debug(s"Found offers with link $offerLink:\n${res.map{_.toString()}.mkString("\n")}")
    res.nonEmpty
  }

  override def addOffer(offer: Offer): Unit = {
    val insert = DBIO.seq(
      offers += offer
    )
    val insertFuture = db.run(insert)
    Await.result(insertFuture, Duration(timeOutMins, "min"))
    logger.debug(s"Successfully inserted offer: ${offer.toString()}")
  }
}