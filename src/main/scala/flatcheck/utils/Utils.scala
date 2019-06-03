package flatcheck.utils

import org.openqa.selenium.WebElement
import com.typesafe.scalalogging.LazyLogging

sealed trait OSType
case object Unix extends OSType
case object Windows extends OSType


object Utils extends LazyLogging {
  def getWebElementDetails(we: WebElement): Map[String, String] = {
    val attrNames = List("type", "class", "style", "id", "name", "onclick")
    val attrs: List[(String, String)] = attrNames.map { attr => attr -> we.getAttribute(attr) }
    val tag = "tag" -> we.getTagName
    val isDisplayed = "isDisplayed" -> we.isDisplayed.toString
    val isEnabled = "isEnabled" -> we.isEnabled.toString
    val isSelected = "isSelected" -> we.isSelected.toString
    val text = "text" -> we.getText
    (tag +: attrs :+ isDisplayed :+ isEnabled :+ isSelected :+ text).toMap
  }

  def getOSType() : OSType = {
    val osName: String = System.getProperty("os.name")
    logger.info(s"The osName string is ")
    if (osName.toLowerCase().contains("windows")) {
      Windows
    } else {
      Unix
    }
  }
}


