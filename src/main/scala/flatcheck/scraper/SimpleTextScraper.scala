package flatcheck.scraper

import com.typesafe.scalalogging.LazyLogging
import flatcheck.db.OffersDS
import flatcheck.utils.WebDriverFactory
import org.openqa.selenium.{By, WebDriver}

import scala.util.{Failure, Success, Try}

class SimpleTextScraper(val driverFactory: WebDriverFactory, val sleepTime: Int) extends LazyLogging {
  def scrapePage(url: String, fields: Map[String, String]) : Map[String, Option[String]] = {
    Try({
      val driver = driverFactory.createWebDriver()
      driver.get(url)
      Thread.sleep(sleepTime)
      val res = fields.map{ case (name, xpath) => name -> {
          Try(driver.findElement(By.xpath(xpath)).getText.trim()) match {
            case Success(res) => Some(res)
            case Failure(err) =>
              logger.trace(s"Could not get text of element with xpath $xpath on page $url, the error was: ${err.getMessage}")
              None
          }
        }
      }
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
