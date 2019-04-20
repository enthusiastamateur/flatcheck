package flatcheck.config

import java.io.{File, FileInputStream}

import com.typesafe.scalalogging.LazyLogging
import org.ini4j.ConfigParser

import scala.util.{Failure, Success, Try}
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
  def getOption(section: String, option: String) : Option[String] = {
    Try(get(section.toLowerCase(), option.toLowerCase())) match {
      case Success(value) => Some(value)
      case Failure(_) =>
        if (section != "general") {
          logger.trace(s"Trying to get option from the general section")
          Try(get("general", option.toLowerCase())).toOption
        } else None
    }
  }

  def safeGetNoDefault(section: String, option: String) : String = getOption(section, option).
    getOrElse(s"Missing config entry: [$section].$option")

  def safeGetString(section: String, option: String, default: Option[String] = None): String = {
    getOption(section, option) match {
      case Some(value) => value
      case None => default.getOrElse(throw new Exception(s"Could not get option for [$section].$option," +
        s" and no default value was supplied"))
    }
  }

  def getConverted[T](section: String, option: String, converter: String => T, default: Option[T] = None): T = {
    Try{converter(safeGetString(section, option))} match {
      case Success(value) => value
      case Failure(ex) => default.getOrElse(
        throw new Exception(s"Could get option [$section].$option (exception was: ${ex.getMessage})," +
          s" and no default value was supplied"))
    }
  }

  def safeGetInt(section: String, option: String, default: Option[Int] = None): Int = {
    getConverted(section, option, _.toInt, default)
  }

  def safeGetDouble(section: String, option: String, default: Option[Double] = None): Double = {
    getConverted(section, option, _.toDouble, default)
  }

  // Load all configs related to the deep scraper
  def getDeepScraperConfig(section: String): Map[String, String] = {
    val colNames : List[String] = offerDetails.baseTableRow.create_*.map(_.name).toList
    colNames.filter{_ != "offerId"}.map{ colName => colName -> safeGetNoDefault(section, colName)}.toMap
  }
}
