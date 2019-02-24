package flatcheck.scraper
import com.typesafe.scalalogging.LazyLogging
import flatcheck.db.OffersDS
import org.openqa.selenium.{By, WebDriver}
import scala.util.{Failure, Success, Try}

class SimpleTextScraper(val driver: WebDriver) extends LazyLogging {
  def scrapePage(url: String, fields: Map[String, String]) : Map[String, Option[String]] = {
    Try({
      driver.get(url)
      fields.map{ case (name, xpath) => name -> {
          Try(driver.findElement(By.xpath(xpath)).getText) match {
            case Success(res) => Some(res)
            case Failure(err) =>
              logger.warn(s"Could not get text of element with xpath $xpath on page $url, the error was: ${err.getMessage}")
              None
          }
        }
      }
    }) match {
      case Success(res) => res
      case Failure(err) =>
        logger.error(s"Could not scrape page with url $url, the error was: ${err.getMessage}")
        Map()
    }
  }
}
