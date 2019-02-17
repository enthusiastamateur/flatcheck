package flatcheck.utils

import org.openqa.selenium.WebElement

object Utils {
  def getWebElementDetails(we : WebElement) : Map[String, String] = {
    val attrNames = List("type", "class", "style", "id", "name", "onclick")
    val attrs : List[(String, String)] = attrNames.map{ attr => attr -> we.getAttribute(attr)}
    val tag = "tag" -> we.getTagName
    val isDisplayed = "isDisplayed" -> we.isDisplayed.toString
    val isEnabled = "isEnabled" -> we.isEnabled.toString
    val isSelected = "isSelected" -> we.isSelected.toString
    val text = "text" -> we.getText
    (tag +: attrs :+ isDisplayed :+ isEnabled :+ isSelected :+ text).toMap
  }
}
