package flatcheck.scraper

import java.util.concurrent.Executors

import com.typesafe.scalalogging.LazyLogging
import flatcheck.config.FlatcheckConfig
import flatcheck.db.{OfferDetails, OffersDS}
import flatcheck.db.Types.{OfferDetail, OfferShortId}
import flatcheck.utils.{Mailer, WebDriverFactory}
import slick.lifted.TableQuery

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex
import scala.collection.JavaConverters._

case object TooManyRetries extends Exception("Too many retries")

class DeepScraper(val driverFactory: WebDriverFactory,
                  val config: FlatcheckConfig,
                  val ds : OffersDS,
                  //implicit val ec: ExecutionContextExecutor,
                  val batchSize : Int = 30,
                  val sleepTime : Int = 5000,
                  val repeatTime: Int = 60000,
                  val maxRetries: Int = 3) extends Thread("DeepScraper") with LazyLogging {
  implicit val ec : ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  val offerDetails = TableQuery[OfferDetails]
  val scraper = new SimpleTextScraper(config, sleepTime, ec)
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
    logger.info(s"The new targets map is: $targets")
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

  def scrapeSitePage(site: String, link: String, id: Long, retry: Int) : Try[OfferDetail] = {
    Try {
      val scraperConfig = config.getDeepScraperConfig(site)
      val resMap = scraper.scrapePage(link, scraperConfig)

      val colNames: IndexedSeq[String] = offerDetails.baseTableRow.create_*.map(_.name).toIndexedSeq
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

  def scrapeNext() : Future[Unit] = Future{
    if (targets.isEmpty) {
      logger.info(s"No links to process in the queue!")
    } else {
      // Process the the links in groups by site, because each group can have different recipients
      val sites: List[String] = config.sections().asScala.toList
      sites.filter{ _ != "general"}.foreach { parseSite =>
        val siteQueue = targets.getOrElse(parseSite, mutable.Queue[OfferShortId]())
        val items = (0 until batchSize).flatMap { _ => if (siteQueue.nonEmpty) {
          Some(siteQueue.dequeue())
        } else None }.toList
        logger.info(s"Found ${items.size} new offers from site $parseSite")
        val newScrapedOffers: List[(String, String, OfferDetail)] = items.flatMap { case (id, site, link) =>
          logger.info(s"Starting deep-scrape of offer with id $id, link $link")
          scrapeSitePage(site, link, id, 0) match {
            case Success(offerDetail) => Some(site, link, offerDetail)
            case Failure(e) =>
              logger.error(s"Could not scrape site: $site, link: $link, the exception was: ${e.getMessage}")
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
        newScrapedOffers.foreach { case (site, link, offerDetail) =>
          Try(ds.addOfferDetail(offerDetail)) match {
            case Failure(exception) => logger.warn(s"Could not add offerDetail $offerDetail to database, the exception was: ${exception.getMessage}")
            case _ =>
          }
        }
        logger.info(s"Finished with the current scrape run for site $parseSite, items remaining in the queue: ${targets.getOrElse(parseSite, mutable.Queue[OfferShortId]()).size}")
      }
    }
  }

  override def run(): Unit = {
    logger.info(s"Started DeepScraper")
    var processedDeepScrapes: Future[Unit] = Future()
    // Give the system time to evaluate the empty future
    Thread.sleep(1000)
    while (true) {
      if (processedDeepScrapes.isCompleted) {
          processedDeepScrapes = scrapeNext()
      } else {
        logger.info(s"Deep scraper is still running, waiting ${repeatTime / 60000} minutes until next scrape ...")
      }
      Thread.sleep(repeatTime)
    }
  }
}
