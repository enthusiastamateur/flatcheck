package flatcheck.config

import java.io.{File, FileInputStream}
import com.typesafe.scalalogging.LazyLogging
import org.ini4j.ConfigParser
import scala.util.Try
import flatcheck.db.OfferDetails
import slick.lifted.TableQuery
import scala.collection.JavaConverters._

class FlatcheckConfig(val iniName: String) extends ConfigParser with LazyLogging {
  val offerDetails = TableQuery[OfferDetails]
  // Do the initial read
  read()

  def read() : Unit =  {
    try {
      read(new FileInputStream(iniName))
    } catch {
      case _: Exception =>
        val currDir = new File("a").getAbsolutePath.dropRight(1)
        logger.error("Couldn't read ini file: " + currDir + iniName)
      //System.exit(1)
    }
  }

  def reRead() : Unit = {
    // Remove all sections first
    sections().asScala.toList.foreach{ section =>
      removeSection(section)
    }
    read()
  }

  // ConfigParser turns all section and option to lowercase!
  def getOption(section: String, option: String) : Option[String] = Try(get(section.toLowerCase(), option.toLowerCase())).toOption

  def safeGet(section: String, option: String) : String = getOption(section, option).getOrElse("")

  // Load all configs related to the deep scraper
  def getDeepScraperConfig(section: String): Map[String, String] = {
    val colNames : List[String] = offerDetails.baseTableRow.create_*.map(_.name).toList
    colNames.filter{_ != "offerId"}.map{ colName => colName -> safeGet(section, colName)}.toMap
  }
}
