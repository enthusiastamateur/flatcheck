package flatcheck.scraper
import com.typesafe.scalalogging.LazyLogging
import org.openqa.selenium.{By, WebDriver}
import slick.jdbc.SQLiteProfile.api._
import scala.util.{Failure, Success, Try}

class SimpleTextScraper(val db: Database, val driver: WebDriver) extends LazyLogging {
  def scrapePage(url: String, fields: Map[String, String]) : Map[String, Option[String]] = {
    Try({
      driver.get(url)
      fields.map{ case (name, xpath) => name ->
        Try(driver.findElement(By.xpath(xpath)).getText).toOption
      }
    }) match {
      case Success(res) => res
      case Failure(err) =>
        logger.info(s"Could not scrape page with url $url, the error was: ${err.getMessage}")
        Map()
    }
  }

  def saveResults(results: Map[String, String]) : Unit = {

  }
}
