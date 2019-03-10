package flatcheck.utils

import com.machinepublishers.jbrowserdriver.{JBrowserDriver, Settings, UserAgent}
import flatcheck.config.FlatcheckConfig
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.{WebDriver, WebElement}
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.ie.InternetExplorerDriver

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

class WebDriverFactory(val options: FlatcheckConfig) {
  def createWebDriver(): WebDriver = {
    options.get("general", "browser").toLowerCase match {
      case "ie" | "internetexplorer" | "explorer" => new InternetExplorerDriver()
      case "chrome" => new ChromeDriver()
      case "firefox" => new FirefoxDriver()
      case "jbrowser" => new JBrowserDriver(Settings.builder()
        .blockAds(true)
        .headless(true)
        .javascript(true)
        .logJavascript(true)
        .ssl("trustanything")
        .userAgent(UserAgent.CHROME)
        .build())
      case rest => throw new IllegalArgumentException(s"Unknown driver: $rest")
    }
  }
}
