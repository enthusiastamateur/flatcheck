package flatcheck.scraper

import com.typesafe.scalalogging.LazyLogging
import flatcheck.config.FlatcheckConfig
import flatcheck.db.{OfferDetails, OffersDS}
import flatcheck.db.Types.{OfferDetail, OfferShortId}
import flatcheck.utils.Mailer
import slick.lifted.TableQuery
import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex
import scala.collection.JavaConverters._

case object TooManyRetries extends Exception("Too many retries")

class DeepScraper(val config: FlatcheckConfig,
                  val ds : OffersDS,
                  val batchSize : Int = 30,
                  val sleepTime : Int = 5000,
                  val repeatTime: Int = 20000,
                  val maxRetries: Int = 3) extends Thread("DeepScraper") with LazyLogging {
  val offerDetails = TableQuery[OfferDetails]
  val scraper = new SimpleTextScraper(config, sleepTime)
  // Change to Map[String, Queue[OfferShortId]
  var targets : mutable.Map[String, mutable.Queue[OfferShortId]] = mutable.Map()
  val patternMHUF: Regex = "([ 0-9]*)(M FT)".r
  val patternHUF: Regex = "([ 0-9]*)(FT)".r
  val patternNM: Regex = "([ 0-9]*)(M2)".r
  val mailer = new Mailer(config)

  def addTargets(tgts: List[OfferShortId]): Unit = {
    tgts.foreach{ tgt =>
      val site = tgt._2
      if (!targets.isDefinedAt(site)) {
        targets(site) = mutable.Queue[OfferShortId]()
      }
      targets(site) += tgt
    }
    logger.info(s"Added ${tgts.size} new offers to scrape")
  }

  def convertPrice(prcStr: String): Int = {
    prcStr.replace(" ", "").replace(",",".").toUpperCase() match {
      case patternHUF(value, _) => value.toInt
      case patternMHUF(value, _) => value.toInt * 1000000
      case _ => -1
    }
  }

  def convertNM(nmStr: String): Int = {
    nmStr.replace(" ", "").toUpperCase() match {
      case patternNM(value, _) => value.toInt
      case _ => -1
    }
  }

  def scrapeSitePage(site: String, link: String, id: Long, retry: Int = 0) : Try[OfferDetail] = {
    Try {
      logger.trace(s"Started scraping site $site, retry = $retry")
      val scraperConfig = config.getDeepScraperConfig(site)
      val resMapOpt = scraper.scrapePage(link, scraperConfig)

      val colNames: IndexedSeq[String] = offerDetails.baseTableRow.create_*.map(_.name).toIndexedSeq
      resMapOpt.map { resMap =>
        (
          id,
          resMap.get(colNames(1)).flatten.getOrElse("").trim(),
          resMap.get(colNames(2)).flatten.getOrElse("").trim(),
          resMap.get(colNames(3)).flatten.getOrElse("").trim(),
          resMap.get(colNames(4)).flatten.getOrElse("").trim(),
          resMap.get(colNames(5)).flatten.getOrElse("").trim(),
          resMap.get(colNames(6)).flatten.getOrElse("").trim(),
          resMap.get(colNames(7)).flatten.getOrElse("").trim(),
          resMap.get(colNames(8)).flatten.getOrElse("").trim(),
          resMap.get(colNames(9)).flatten.getOrElse("").trim()
        )
      }.getOrElse(throw new Exception(s"SimpleTextScraper could not scrape site $site"))
    } match {
      case Success(value) => Success(value)
      case Failure(e) =>
        logger.warn(s"Encountered exception during the scraping of site: $site, link: $link, retry: $retry" +
          s"the exception was: ${e.getMessage}\n")
        if (retry < maxRetries) {
          scrapeSitePage(site, link, id, retry + 1)
        } else {
          Failure(TooManyRetries)
        }
    }
  }

  def scrapeNext() : Unit = {
    if (targets.isEmpty) {
      logger.info(s"No links to process in the queue!")
    } else {
      // Process the the links in groups by site, because each group can have different recipients
      val sites: List[String] = config.sections().asScala.toList
      sites.filter{ _ != "general"}.foreach { parseSite =>
        Try {
          val siteQueue = targets.getOrElse(parseSite, mutable.Queue[OfferShortId]())
          val items = (0 until batchSize).flatMap { _ =>
            if (siteQueue.nonEmpty) {
              Some(siteQueue.dequeue())
            } else None
          }.toList
          logger.info(s"Found ${items.size} new offers from site $parseSite")
          val newScrapedOffers: List[(String, String, OfferDetail)] = items.flatMap { case (id, site, link) =>
            logger.info(s"Starting deep-scrape of offer with id $id, link $link")
            scrapeSitePage(site, link, id) match {
              case Success(offerDetail) => Some(site, link, offerDetail)
              case Failure(e) =>
                logger.error(s"Could not scrape site: $site, link: $link, the exception was: ${e.getMessage}")
                logger.info(s"Adding back site to the queue")
                addTargets(List((id, site, link)))
                None
            }
          }
          // Send notification email
          val sendTo = config.get(parseSite, "sendto").split(",").toList
          if (newScrapedOffers.nonEmpty) {
            if (sendTo.nonEmpty) {
              mailer.sendOfferNotification(newScrapedOffers, sendTo)
            } else {
              logger.info(s"No recipients marked for site $parseSite, will not send any emails")
            }
          } else {
            logger.info(s"No new hits for site $parseSite")
          }
          // Now persist the data
          newScrapedOffers.foreach { case (_, _, offerDetail) =>
            Try(ds.addOfferDetail(offerDetail)) match {
              case Failure(exception) => logger.warn(s"Could not add offerDetail $offerDetail to database, the exception was: ${exception.getMessage}")
              case _ =>
            }
          }
          logger.info(s"Finished with the current scrape run for site $parseSite, items remaining in the queue: ${targets.getOrElse(parseSite, mutable.Queue[OfferShortId]()).size}")
        } match {
          case Success(_) => logger.trace(s"Successfully finished with site $parseSite")
          case Failure(exception) => logger.warn(s"Deep-scraping site $parseSite failed with " +
            s"exception ${exception.getMessage}. Moving on to next site...")
        }
      }
    }
  }

  override def run(): Unit = {
    logger.info(s"Started DeepScraper")
    while (true) {
      Try { scrapeNext() } match {
        case Success(_) => logger.trace(s"Successfully finished deepscraper iteration, will " +
          s"wait ${repeatTime/1000} seconds")
        case Failure(exception) => logger.warn(s"Deepscraper iteration has failed with " +
          s"exception ${exception.getMessage}. Retrying in ${repeatTime/1000} seconds")
      }
      Thread.sleep(repeatTime)
    }
  }
}
