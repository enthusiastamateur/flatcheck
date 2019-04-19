package flatcheck.scraper

import com.typesafe.scalalogging.LazyLogging
import flatcheck.config.FlatcheckConfig
import flatcheck.utils.SafeDriver
import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success, Try}

class SimpleTextScraper(val options: FlatcheckConfig, val sleepTime: Int, val ec: ExecutionContextExecutor) extends LazyLogging {
  def scrapePage(url: String, fields: Map[String, String]) : Map[String, Option[String]] = {
    Try({
      logger.trace(s"Starting loading of url $url")
      val driver = new SafeDriver(options, logger, ec)
      driver.get(url)
      logger.trace(s"Starting scraping of url $url")
      val res = fields.map{ case (name, xpath) => name -> {
          Try(driver.findElementByXPath(xpath, 0, 0).getText.trim()) match {
            case Success(result) => Some(result)
            case Failure(err) =>
              logger.trace(s"Could not get text of element with xpath $xpath on page $url, the error was: ${err.getMessage}")
              None
          }
        }
      }
      // Destroy instance
      driver.quit()
      res
    }) match {
      case Success(res) =>
        logger.trace(s"The raw scraped results for url  $url: ${res.mkString(",")}")
        res
      case Failure(err) =>
        logger.error(s"Could not scrape page with url $url, the error was: ${err.getMessage}")
        Map()
    }
  }
}
