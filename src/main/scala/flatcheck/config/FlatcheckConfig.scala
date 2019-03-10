package flatcheck.config

import org.ini4j.ConfigParser
import scala.util.Try
import flatcheck.db.OfferDetails
import slick.lifted.TableQuery

class FlatcheckConfig extends ConfigParser {
  val offerDetails = TableQuery[OfferDetails]

  // ConfigParser turns all section and option to lowercase!
  def getOption(section: String, option: String) : Option[String] = Try(get(section.toLowerCase(), option.toLowerCase())).toOption

  def safeGet(section: String, option: String) : String = getOption(section, option).getOrElse("")

  // Load all configs related to the deep scraper
  def getDeepScraperConfig(section: String): Map[String, String] = {
    val colNames : List[String] = offerDetails.baseTableRow.create_*.map(_.name).toList
    colNames.filter{_ != "offerId"}.map{ colName => colName -> safeGet(section, colName)}.toMap
  }
}
