package flatcheck.scraper
import com.typesafe.scalalogging.LazyLogging
import flatcheck.db.OffersDS
import org.openqa.selenium.{By, WebDriver}
import scala.util.{Failure, Success, Try}

class SimpleTextScraper(val driver: WebDriver, val sleepTime: Int) extends LazyLogging {
  def scrapePage(url: String, fields: Map[String, String]) : Map[String, Option[String]] = {
    Try({
      driver.get(url)
      Thread.sleep(sleepTime)
      fields.map{ case (name, xpath) => name -> {
          Try(driver.findElement(By.xpath(xpath)).getText.trim()) match {
            case Success(res) => Some(res)
            case Failure(err) =>
              logger.warn(s"Could not get text of element with xpath $xpath on page $url, the error was: ${err.getMessage}")
              None
          }
        }
      }
    }) match {
      case Success(res) =>
        logger.info(s"The raw scraped results for url  $url: ${res.mkString(",")}")
        res
      case Failure(err) =>
        logger.error(s"Could not scrape page with url $url, the error was: ${err.getMessage}")
        Map()
    }
  }
}
