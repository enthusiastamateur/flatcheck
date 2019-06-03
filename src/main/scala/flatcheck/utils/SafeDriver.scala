package flatcheck.utils

import java.util.concurrent.TimeUnit
import java.util.logging.Level

import com.machinepublishers.jbrowserdriver.{JBrowserDriver, Settings, UserAgent}
import com.typesafe.scalalogging.Logger
import flatcheck.config.FlatcheckConfig
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.ie.InternetExplorerDriver
import org.openqa.selenium.{By, JavascriptExecutor, WebDriver, WebElement}

import scala.concurrent._
import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

class SafeDriver(val options: FlatcheckConfig, val logger: Logger) {
  private val timeoutSeconds = options.safeGetInt("general", "safedrivertimeout", Some(10))
  private val waitForLoad = options.safeGetInt("general", "waitforload", Some(8))
  private val maxRetry = options.safeGetInt("general", "maxretry", Some(2))
  private var driver : WebDriver = createWebDriver()
  logger.debug(s"Prewarming driver")
  Try{driver.get("www.google.com")}
  private val binaryLocation = Utils.getOSType() match {
    case Windows => "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
    case Unix => "/usr/bin/google-chrome"
  }
  private val chromeDriverLocation = Utils.getOSType() match {
    case Windows => "./chrome/chromedriver_74.0.3729.6.exe"
    case Unix => "./chrome/chromedriver_74.0.3729.6_linux_64"
  }

  def createWebDriver(): WebDriver = {
    val driver = options.get("general", "browser").toLowerCase match {
      case "ie" | "internetexplorer" | "explorer" => new InternetExplorerDriver()
      case "chrome" => {
        System.setProperty("webdriver.chrome.driver", chromeDriverLocation)
        val options = new ChromeOptions().setHeadless(true).
          setBinary(binaryLocation).
          addArguments("start-maximized", "disable-infobars", "--disable-extensions", "--incognito")
        new ChromeDriver(options)
      }
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

  def refresh(maxRetry: Int = maxRetry, retry: Int = 0) : Unit = {
    logger.trace(s"Started page refresh, retry = $retry")
    Try{driver.navigate().refresh()} match {
      case Success(_) =>
        logger.trace(s"Webpage successfully refreshed")
      case Failure(e) =>
        if (retry < maxRetry) {
          logger.warn(s"Refreshing the site at the current url failed, trying again from a new instance")
          val url = getCurrentUrl()
          reset()
          get(url)
          refresh(maxRetry, retry+1)
        } else {
          logger.warn(s"Refreshing the site at the current url failed, and we have used all $maxRetry retries. " +
            s"Rethrowing the exception...")
          throw e
        }
    }
  }

  def getCurrentUrl(maxRetry: Int = maxRetry, retry: Int = 0): String = {
    logger.trace(s"Started getCurrentURL, retry = $retry")
    Try{driver.getCurrentUrl} match {
      case Success(value) =>
        value
      case Failure(e) =>
        if (retry < maxRetry) {
          logger.warn(s"Getting the driver url failed, " +
            s"trying again after reloading the page and waiting $timeoutSeconds seconds")
          Try{driver.navigate().refresh()} // To avoid circular reference, we cannot call refresh from here
          Thread.sleep(timeoutSeconds * 1000)
          getCurrentUrl(maxRetry, retry+1)
        } else {
          logger.warn(s"Getting the driver url failed, and we have used all $maxRetry retries. " +
            s"Rethrowing the exception...")
          throw e
        }
    }
  }

  def printDriverLogs(): Unit = {
    val logTypes = driver.manage().logs().getAvailableLogTypes
    logTypes.forEach{ logType =>
      val logs = driver.manage().logs().get(logType)
      logs.forEach { logEntry =>
        val message = s"Webdriver log: [Timestamp = ${new java.util.Date(logEntry.getTimestamp).toString}, message = ${logEntry.getMessage}]"
        logEntry.getLevel match {
          case Level.FINE | Level.FINER | Level.FINEST | Level.CONFIG => logger.trace(message)
          case Level.INFO => logger.info(message)
          case Level.WARNING => logger.warn(message)
          case Level.SEVERE => logger.error(message)
        }
      }
    }
  }

  def get(url: String, waitForLoad: Int = waitForLoad, maxRetry: Int = maxRetry, retry: Int = 0) : Unit = {
    logger.trace(s"Started loading page $url (retry = $retry), will wait $waitForLoad seconds for page to load completely")
    val res = Try{
      driver.get(url)
      Thread.sleep(waitForLoad * 1000)
      logger.trace(s"Finished sleep after driver.get")
    }
    printDriverLogs()
    res match {
      case Success(_) => Unit
      case Failure(e) =>
        if (retry < maxRetry) {
          logger.warn(s"Loading of url $url has failed with message ${e.getMessage}, trying again with a fresh instance")
          reset()
          get(url, waitForLoad, maxRetry, retry+1)
        } else {
          logger.warn(s"Loading of url $url has timed out, and we have used all $maxRetry retries. Rethrowing the exception...")
          throw e
        }
    }
  }

  def findElementsByXPath(xpath: String, maxRetry: Int = maxRetry, retry: Int = 0) : List[WebElement] = {
    logger.trace(s"Started looking for elements by XPath $xpath, retry = $retry")
    val res  = Try{
      driver.findElements(By.xpath(xpath)).asScala.toList
    }
    printDriverLogs()
    logger.trace(s"Finished looking for elements by XPath $xpath")
    res match {
      case Success(value) =>
        logger.trace(s"The number of found elements is: ${value.size}")
        value
      case Failure(e) =>
        if (retry < maxRetry) {
          logger.warn(s"Finding of elements by XPath $xpath failed with message ${e.getMessage}, trying again after reloading the page and waiting $timeoutSeconds seconds")
          refresh()
          Thread.sleep(timeoutSeconds * 1000)
          findElementsByXPath(xpath, maxRetry, retry+1)
        } else {
          logger.warn(s"Finding of elements by XPath $xpath has timed out, and we have used all $maxRetry retries. Rethrowing the exception...")
          throw e
        }
    }
  }

  def findElementByXPath(xpath: String, maxRetry: Int = maxRetry, retry: Int = 0) : WebElement = {
    val res = findElementsByXPath(xpath, maxRetry, retry)
    res.size match {
      case 0 => throw new NoSuchElementException(s"Did not find element with XPath selector $xpath")
      case rest => if (rest > 1) {
        logger.warn(s"Found more than one element with XPath selector:$xpath. " +
          s"Going to use the first element in the list, the complete list is $res")
      }
        res.head
    }
  }

  // Will return true if click happened, false otherwise
  def clickElementByXPath(xpath: String, waitForLoad: Int = waitForLoad, maxRetry: Int = maxRetry, retry: Int = 0): Boolean = {
    logger.trace(s"Started clicking on element by XPath $xpath, retry = $retry")
    val res = Try {
      Try(findElementByXPath(xpath, maxRetry)) match {
        case Success(button) =>
          if (button.isEnabled) {
            logger.trace(s"Clicking on next page button with text '${button.getText}', " +
              s"will wait $waitForLoad seconds afterwards")
            logger.trace(s"Details of the nextPageButton:\n${Utils.getWebElementDetails(button).mkString("\n")}")
            val jse = driver.asInstanceOf[JavascriptExecutor]
            jse.executeScript("arguments[0].click();", button)
            //button.click()
            Thread.sleep(waitForLoad * 1000)
            true
          } else {
            logger.trace(s"Element with text ${button.getText} is not enabled, proceeding with no-op")
            false
          }
        case Failure(_: NoSuchElementException) => false
        case Failure(e) => throw e
      }
    }
    printDriverLogs()
    logger.trace(s"Finished clicking on element by XPath $xpath")
    res match {
      case Success(value) =>
        logger.trace(s"The click return value is $value")
        value
      case Failure(e) =>
        if (retry < maxRetry) {
          logger.warn(s"Clicking on element by XPath $xpath failed with message ${e.getMessage}, " +
            s"trying again after reloading the page and waiting $timeoutSeconds seconds")
          refresh()
          Thread.sleep(timeoutSeconds * 1000)
          clickElementByXPath(xpath, waitForLoad, maxRetry, retry+1)
        } else {
          logger.warn(s"Clicking of element by XPath $xpath has timed out, and we have used all $maxRetry retries. " +
            s"Rethrowing the exception...")
          throw e
        }
    }
  }

  def executeJavascript(script: String, timeoutSeconds : Int = timeoutSeconds, maxRetry: Int = maxRetry, retry: Int = 0): Object = {
    logger.trace(s"Started executing Javascript, retry = $retry")
    val jse = driver.asInstanceOf[JavascriptExecutor]
    val res = Try {
      logger.trace(s"Started execution of JS script $script")
      val res = jse.executeScript(script)
      logger.trace(s"Finished execution of JS script $script, return value is: $res")
      res
    }
    printDriverLogs()
    res match {
      case Success(value) => value
      case Failure(e: TimeoutException) =>
        if (retry < maxRetry) {
          logger.warn(s"Execution of JS script $script has timed out, trying again after reloading the page and " +
            s"waiting $timeoutSeconds seconds")
          refresh()
          Thread.sleep(timeoutSeconds * 1000)
          executeJavascript(script, timeoutSeconds, maxRetry, retry+1)
        } else {
          logger.warn(s"Execution of JS script $script has timed out again, and we have used all $maxRetry retries. " +
            s"Rethrowing the exception...")
          throw e
        }
      case Failure(rest) =>
        logger.warn(s"Encountered exception during execution of JS script $script, the message was: ${rest.getMessage}")
        throw rest
    }
  }

  def reset(): Unit = {
    quit()
    Try{driver = createWebDriver()} match {
      case Success(_) => logger.debug("New driver instance successfully created")
      case Failure(exception) => logger.debug(s"Failed to create new driver instance, " +
        s"the exception was: ${exception.getMessage}")
    }
  }

  def quit(): Unit = {
    Try{driver.quit()} match {
      case Success(_) => logger.debug("Driver successfully closed")
      case Failure(exception) => logger.debug(s"Failed to close driver, the exception was: ${exception.getMessage}")
    }
  }
}
