package flatcheck.scraper

import com.typesafe.scalalogging.LazyLogging
import flatcheck.config.FlatcheckConfig
import flatcheck.db.{OfferDetails, OffersDS}
import flatcheck.db.Types.{OfferDetail, OfferShortId}
import org.openqa.selenium.WebDriver
import slick.lifted.TableQuery

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scala.util.matching.Regex

class DeepScraper(val driver: WebDriver,
                  val config: FlatcheckConfig,
                  val ds : OffersDS,
                  val batchSize : Int = 10,
                  val sleepTime : Int = 5000) extends LazyLogging {

  val scraper = new SimpleTextScraper(driver, sleepTime)
  val offerDetails = TableQuery[OfferDetails]
  var targets : mutable.Queue[OfferShortId] = mutable.Queue()
  val patternMHUF: Regex = "([ 0-9]*)(M FT)".r
  val patternHUF: Regex = "([ 0-9]*)(FT)".r
  val patternNM: Regex = "([ 0-9]*)(M2)".r

  def addTargets(tgts: List[OfferShortId]): Unit = {
    targets ++= tgts
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

  def scrapeSitePage(site: String, link: String, id: Long) : OfferDetail = {
    val scraperConfig = config.getDeepScraperConfig(site)
    val resMap = scraper.scrapePage(link, scraperConfig)

    val colNames : IndexedSeq[String] = offerDetails.baseTableRow.create_*.map(_.name).toIndexedSeq
    (
      id,
      convertPrice(resMap.get(colNames(1)).flatten.getOrElse("")),
      convertNM(resMap.get(colNames(2)).flatten.getOrElse("")),
      resMap.get(colNames(3)).flatten.flatMap{x => Try(x.split(' ').head.toInt).toOption}.getOrElse(-1),
      resMap.get(colNames(4)).flatten.getOrElse(""),
      resMap.get(colNames(5)).flatten.getOrElse(""),
      resMap.get(colNames(6)).flatten.getOrElse(""),
      resMap.get(colNames(7)).flatten.getOrElse(""),
      resMap.get(colNames(8)).flatten.getOrElse("")
    )
  }

  def scrapeNext() : Future[Int] = Future{
    if (targets.isEmpty) {
      logger.info(s"No links to process in the queue...")
      0
    } else {
      val items = (0 until batchSize).flatMap { _ => if (targets.nonEmpty) Some(targets.dequeue()) else None }.toList
      items.foreach { case (id, site, link) =>
        logger.info(s"Starting deep-scrape of offer with id $id, link $link")
        val offerDetail = scrapeSitePage(site, link, id)
        ds.addOfferDetail(offerDetail)
      }
      logger.info(s"Finished with the current run, processed ${items.size} links. Remaining in the queue: ${targets.size}")
      items.size
    }
  }
}
