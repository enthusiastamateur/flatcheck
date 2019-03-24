package flatcheck.db

import java.sql.Timestamp

import com.typesafe.scalalogging.LazyLogging
import flatcheck.db.Types._
import slick.jdbc.SQLiteProfile.api._
import slick.lifted.{Query, TableQuery}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

trait OffersDS {
  def getOfferIdByLink(offerLink: String) : Option[Long]
  def addOffer(offer: Offer) : Long
  def addOfferDetail(offerDetail: OfferDetail): Boolean
  def updateOfferLastSeen(id: Long, lastSeen: Timestamp) : Boolean
  def getOfferWithDetails(id: Long): Option[OfferWithDetails]
  def getOffersWithoutDetails: List[Offer]
}


class OffersSQLiteDataSource(val db: Database, val timeOutMins: Long = 5) extends OffersDS with LazyLogging  {
  val offers = TableQuery[Offers]
  val offerDetails = TableQuery[OfferDetails]

  logger.trace("Initializing offers table if needed...")
  Await.result(db.run(DBIO.seq(offers.schema.createIfNotExists)), Duration(timeOutMins, "min"))
  Await.result(db.run(DBIO.seq(offerDetails.schema.createIfNotExists)), Duration(timeOutMins, "min"))

  private def offerExists(offerId: Long) : Boolean = {
    val filterQuery: Query[Offers, Offer, Seq] = offers.filter(_.offerId === offerId)
    val res : Seq[Offer] = Await.result(db.run(filterQuery.result), Duration(timeOutMins, "min"))
    res.nonEmpty
  }

  private def offerDetailExists(offerId: Long) : Boolean = {
    val filterQuery = offerDetails.filter(_.offerId === offerId)
    val res : Seq[OfferDetail] = Await.result(db.run(filterQuery.result), Duration(timeOutMins, "min"))
    res.nonEmpty
  }

  override def getOfferIdByLink(offerLink: String): Option[Long] = {
    val filterQuery: Query[Offers, Offer, Seq] = offers.filter(_.link === offerLink)
    val res : List[Offer] = Await.result(db.run(filterQuery.result), Duration(timeOutMins, "min")).toList
    logger.trace(s"Found offers with link $offerLink:\n${res.map{_.toString()}.mkString("\n")}")
    res match {
      case Nil => None
      case record :: Nil => Some(record._1)
      case record :: _ =>
        logger.warn(s"Found multiple offers with the same link $offerLink. Returning the first result.")
        Some(record._1)
    }
  }

  override def addOffer(offer: Offer): Long = {
    val insert = offers returning offers.map(_.offerId) += offer
    val res = Await.result(db.run(insert), Duration(timeOutMins, "min"))
    logger.trace(s"Successfully inserted offer: ${offer.toString()}, it's id is $res")
    res
  }

  override def addOfferDetail(offerDetail: OfferDetail): Boolean = {
    if (offerExists(offerDetail._1)) {
      if (offerDetailExists(offerDetail._1)) {
        val filterQuery = offerDetails.filter(_.offerId === offerDetail._1)
        Await.result(db.run(filterQuery.update(offerDetail)), Duration(timeOutMins, "min"))
        logger.debug(s"Successfully updated offerDetail: $offerDetail...")
      } else {
        val insert = offerDetails += offerDetail
        Await.result(db.run(insert), Duration(timeOutMins, "min"))
        logger.debug(s"Successfully inserted offerDetails " +
          s"(${offerDetail._1},${offerDetail._2},${offerDetail._3},${offerDetail._4},${offerDetail._5}," +
          s"${offerDetail._6},${offerDetail._7},${offerDetail._8},${offerDetail._9.take(20)}...)")
      }
      true
    } else {
      logger.warn(s"Did not find offer with id ${offerDetail._1}, could not add details $offerDetail")
      false
    }
  }

  override def updateOfferLastSeen(offerId: Long, lastSeen: Timestamp) : Boolean = {
    if (offerExists(offerId)) {
      val filterQuery = for { o <- offers if o.offerId === offerId } yield o.lastSeen
      Await.result(db.run(filterQuery.update(lastSeen)), Duration(timeOutMins, "min"))
      logger.debug(s"Successfully updated lastSeen to $lastSeen of offer with id $offerId")
      true
    } else {
      logger.warn(s"Did not find offer with id $offerId, could not update its lastSeen timestamp")
      false
    }
  }

  override def getOfferWithDetails(id: Long): Option[OfferWithDetails] = {
    val withDetailsQuery = for {
      (offer, offerDetails) <- offers.join(offerDetails).on(_.offerId === _.offerId)
    } yield (offer.offerId,
      offer.site,
      offer.link,
      offer.firstSeen,
      offer.lastSeen,
      offerDetails.priceHUF,
      offerDetails.sizeSM,
      offerDetails.roomsNum,
      offerDetails.address,
      offerDetails.area,
      offerDetails.description,
      offerDetails.floor,
      offerDetails.flatCondition)
    val filteredQuery = withDetailsQuery.filter{ _._1 === id}
    Await.result(db.run(filteredQuery.result), Duration(timeOutMins, "min")) match {
      case Nil => None
      case record :: Nil => Some(record)
      case record :: _ =>
        logger.warn(s"Found multiple records with offerId $id. Returning first result.")
        Some(record)
    }
  }

  override def getOffersWithoutDetails: List[Offer] = {
    val withoutDetailsQuery = for {
      (offer, offerDetails) <-  offers.joinLeft(offerDetails).on(_.offerId === _.offerId).filter{ case(o, od) => !od.isDefined}
    } yield (offer.offerId,
      offer.site,
      offer.link,
      offer.firstSeen,
      offer.lastSeen)
    Await.result(db.run(withoutDetailsQuery.result), Duration(timeOutMins, "min")) match {
      case Nil =>
        logger.debug(s"Well done, all records have their offer details scraped!")
        Nil
      case some =>
        logger.debug(s"Found ${some.size} records which do not have their details scraped!")
        some.toList
    }
  }
}