package flatcheck.utils

import java.util.concurrent.TimeUnit
import com.machinepublishers.jbrowserdriver.{JBrowserDriver, Settings, UserAgent}
import flatcheck.config.FlatcheckConfig
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.{WebDriver, WebElement}
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.ie.InternetExplorerDriver
import com.typesafe.scalalogging.{LazyLogging, Logger}

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
}

class WebDriverFactory(val options: FlatcheckConfig) {
  def createWebDriver(): WebDriver = {
    val driver = options.get("general", "browser").toLowerCase match {
      case "ie" | "internetexplorer" | "explorer" => new InternetExplorerDriver()
      case "chrome" => new ChromeDriver()
      case "firefox" => new FirefoxDriver()
      case "jbrowser" => new JBrowserDriver(Settings.builder()
        .processes(4)
        .blockAds(true)
        .headless(true)
        .javascript(true)
        .logJavascript(true)
        .logger(null)
        .logsMax(1000)
        .logWarnings(true)
        .ssl("trustanything")
        .userAgent(UserAgent.CHROME)
        .quickRender(true)
        .build())
      case rest => throw new IllegalArgumentException(s"Unknown driver: $rest")
    }
    driver.manage().timeouts().pageLoadTimeout(10L, TimeUnit.SECONDS)
    driver.manage().timeouts().setScriptTimeout(10L, TimeUnit.SECONDS)
    driver.manage().timeouts().implicitlyWait(10L, TimeUnit.SECONDS)
    driver
  }
}


