package flatcheck.scraper

import com.typesafe.scalalogging.LazyLogging
import flatcheck.config.FlatcheckConfig
import flatcheck.db.{OfferDetails, OffersDS}
import flatcheck.db.Types.{OfferDetail, OfferShortId}
import flatcheck.utils.WebDriverFactory
import slick.lifted.TableQuery

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

case object TooManyRetries extends Exception("Too many retries")

class DeepScraper(val driverFactory: WebDriverFactory,
                  val config: FlatcheckConfig,
                  val ds : OffersDS,
                  val batchSize : Int = 10,
                  val sleepTime : Int = 5000,
                  val repeatTime: Int = 60000,
                  val maxRetries: Int = 3) extends Thread("ScrapeTimer") with LazyLogging {

  val offerDetails = TableQuery[OfferDetails]
  var targets : mutable.Queue[OfferShortId] = mutable.Queue()
  val patternMHUF: Regex = "([ 0-9]*)(M FT)".r
  val patternHUF: Regex = "([ 0-9]*)(FT)".r
  val patternNM: Regex = "([ 0-9]*)(M2)".r

  def addTargets(tgts: List[OfferShortId]): Unit = {
    targets ++= tgts
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

  def scrapeSitePage(site: String, link: String, id: Long, retry: Int) : Try[OfferDetail] = {
    Try {
      val scraperConfig = config.getDeepScraperConfig(site)
      val scraper = new SimpleTextScraper(driverFactory.createWebDriver(), sleepTime)
      val resMap = scraper.scrapePage(link, scraperConfig)

      val colNames: IndexedSeq[String] = offerDetails.baseTableRow.create_*.map(_.name).toIndexedSeq
      (
        id,
        convertPrice(resMap.get(colNames(1)).flatten.getOrElse("")),
        resMap.get(colNames(2)).flatten.getOrElse(""),
        resMap.get(colNames(3)).flatten.getOrElse(""),
        resMap.get(colNames(4)).flatten.getOrElse(""),
        resMap.get(colNames(5)).flatten.getOrElse(""),
        resMap.get(colNames(6)).flatten.getOrElse(""),
        resMap.get(colNames(7)).flatten.getOrElse(""),
        resMap.get(colNames(8)).flatten.getOrElse("")
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
      logger.info(s"No links to process in the queue, waiting ${repeatTime / 60000} minutes until next scrape ...")
      Thread.sleep(repeatTime)
    } else {
      val items = (0 until batchSize).flatMap { _ => if (targets.nonEmpty) Some(targets.dequeue()) else None }.toList
      items.foreach { case (id, site, link) =>
        logger.info(s"Starting deep-scrape of offer with id $id, link $link")
        scrapeSitePage(site, link, id, 0) match {
          case Success(offerDetail) => Try(ds.addOfferDetail(offerDetail))
          case Failure(e) => logger.error(s"Could not scrape site: $site, link: $link, the exception was: ${e.getMessage}")
        }
      }
      logger.info(s"Finished with the current run, processed ${items.size} links. Remaining in the queue: ${targets.size}")
    }
  }

  override def run(): Unit = {
    logger.info(s"Started thread")
    var processedDeepScrapes: Future[Unit] = Future()
    while (true) {
      // Make sure we don't kill the CPU
      Thread.sleep(1000)
      if (processedDeepScrapes.isCompleted) {
          processedDeepScrapes = scrapeNext()
      } else {
        logger.info(s"Deep scraper is still running, waiting ${repeatTime / 60000} minutes until next scrape ...")
        Thread.sleep(repeatTime)
      }
    }
  }
}
