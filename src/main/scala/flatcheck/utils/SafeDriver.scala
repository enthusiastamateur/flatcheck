package flatcheck.utils

import java.util.concurrent.{Executors, TimeoutException}
import java.util.logging.Level

import com.machinepublishers.jbrowserdriver.JBrowserDriver
import com.typesafe.scalalogging.Logger
import flatcheck.config.FlatcheckConfig
import org.openqa.selenium.{By, JavascriptExecutor, WebDriver, WebElement}

import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

class SafeDriver(val options: FlatcheckConfig, val logger: Logger, implicit val ec: ExecutionContextExecutor, val timeoutSeconds: Int = 20) {
  //implicit val ec : ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(5))
  private val driverFactory = new WebDriverFactory(options)
  private var driver : WebDriver = driverFactory.createWebDriver()

  def getCurrentUrl(retry: Int = 0, maxRetry : Int = 2): String = {
    Try{driver.getCurrentUrl} match {
      case Success(value) =>
        value
      case Failure(e) =>
        if (retry < maxRetry) {
          logger.warn(s"Getting the driver url failed, trying again after reloading the page and waiting $timeoutSeconds seconds")
          driver.navigate().refresh()
          Thread.sleep(timeoutSeconds * 1000)
          getCurrentUrl(retry+1, maxRetry)
        } else {
          logger.warn(s"Getting the driver url failed, and we have used all $maxRetry retries. Rethrowing the exception...")
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

  def get(url: String, waitForLoad: Int = 8, retry: Int = 0, maxRetry: Int = 2) : Unit = {
    if (waitForLoad > timeoutSeconds) throw new IllegalArgumentException(s"waitForLoad ($waitForLoad) cannot exceed timeoutSeconds ($timeoutSeconds)")
    logger.trace(s"Started loading page $url, will wait $waitForLoad seconds for page to load completely")
    val loadResult = Future{
      blocking {
        driver.get(url)
        Thread.sleep(waitForLoad * 1000)
        logger.trace(s"Finished sleep in get")
        1
      }
    }
    val res = Try(Await.result(loadResult, Duration(timeoutSeconds, "sec")))
    printDriverLogs()
    logger.trace(s"Finished waiting for page load of $url")
    res match {
      case Success(_) => Unit
      case Failure(e) =>
        if (retry < maxRetry) {
          logger.warn(s"Loading of url $url has failed with message ${e.getMessage}, trying again with a fresh instance")
          driver.quit()
          driver = driverFactory.createWebDriver()
          get(url, waitForLoad, retry+1, maxRetry)
        } else {
          logger.warn(s"Loading of url $url has timed out, and we have used all $maxRetry retries. Rethrowing the exception...")
          throw e
        }
    }
  }

  def findElementsByXPath(xpath: String, retry: Int = 0, maxRetry: Int = 2) : List[WebElement] = {
    logger.trace(s"Started looking for elements by XPath $xpath")
    val jseResult  = Future{
      blocking {
        val res = driver.findElements(By.xpath(xpath)).asScala.toList
        res
      }
    }
    val res = Try(Await.result(jseResult, Duration(timeoutSeconds, "sec")))
    printDriverLogs()
    logger.trace(s"Finished looking for elements by XPath $xpath")
    res match {
      case Success(value) =>
        logger.trace(s"The number of found elements is: ${value.size}")
        value
      case Failure(e) =>
        if (retry < maxRetry) {
          logger.warn(s"Finding of elements by XPath $xpath failed with message ${e.getMessage}, trying again after reloading the page and waiting $timeoutSeconds seconds")
          driver.navigate().refresh()
          Thread.sleep(timeoutSeconds * 1000)
          findElementsByXPath(xpath, retry+1, maxRetry)
        } else {
          logger.warn(s"Finding of elements by XPath $xpath has timed out, and we have used all $maxRetry retries. Rethrowing the exception...")
          throw e
        }
    }
  }

  def findElementByXPath(xpath: String, retry: Int = 0, maxRetry: Int = 2) : WebElement = {
    val res = findElementsByXPath(xpath, retry, maxRetry)
    res.size match {
      case 0 => throw new NoSuchElementException(s"Did not find element with XPath selector $xpath")
      case rest => if (rest > 1) {
        logger.trace(s"Found more than one element with XPath selector:$xpath. " +
          s"Going to use the first element in the list, the complete list is $res")
      }
        res.head
    }
  }

  // Will return true if click happened, false otherwise
  def clickElementByXPath(xpath: String, waitForLoad: Int = 8, retry: Int = 0, maxRetry: Int = 2): Boolean = {
    logger.trace(s"Started clicking on element by XPath $xpath")
    val clickResult = Future {
      blocking {
        Try(findElementByXPath(xpath, retry, maxRetry)) match {
          case Success(button) =>
            if (button.isEnabled) {
              logger.trace(s"Clicking on next page button with text '${button.getText}'...")
              logger.trace(s"Details of the nextPageButton:\n${Utils.getWebElementDetails(button).mkString("\n")}")
              button.click()
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
    }
    val res = Try(Await.result(clickResult, Duration(timeoutSeconds, "sec")))
    printDriverLogs()
    logger.trace(s"Finished clicking on element by XPath $xpath")
    res match {
      case Success(value) =>
        logger.trace(s"The click return value is $value")
        value
      case Failure(e) =>
        if (retry < maxRetry) {
          logger.warn(s"Clicking on element by XPath $xpath failed with message ${e.getMessage}, trying again after reloading the page and waiting $timeoutSeconds seconds")
          driver.navigate().refresh()
          Thread.sleep(timeoutSeconds * 1000)
          clickElementByXPath(xpath, waitForLoad, retry, maxRetry)
        } else {
          logger.warn(s"Clicking of element by XPath $xpath has timed out, and we have used all $maxRetry retries. Rethrowing the exception...")
          throw e
        }
    }
  }

  def executeJavascript(script: String, timeoutSeconds : Int = 10, retry: Int = 0, maxRetry : Int = 2): Object = {
    val jse = driver.asInstanceOf[JavascriptExecutor]
    val jseResult  = Future{
      blocking {
        logger.trace(s"Started execution of JS script $script")
        val res = jse.executeScript(script)
        logger.trace(s"Finished execution of JS script $script, return value is: $res")
        res
      }
    }
    val res = Try(Await.result(jseResult, Duration(timeoutSeconds, "sec")))
    res match {
      case Success(value) => value
      case Failure(e: TimeoutException) =>
        if (retry < maxRetry) {
          logger.warn(s"Execution of JS script $script has timed out, trying again after reloading the page and waiting $timeoutSeconds seconds")
          driver.navigate().refresh()
          Thread.sleep(timeoutSeconds * 1000)
          executeJavascript(script, timeoutSeconds, retry+1, maxRetry)
        } else {
          logger.warn(s"Execution of JS script $script has timed out again, and we have used all $maxRetry retries. Rethrowing the exception...")
          throw e
        }
      case Failure(rest) =>
        logger.warn(s"Encountered exception during execution of JS script $script, the message was: ${rest.getMessage}")
        throw rest
    }
  }

  def reset(): Unit = {
    quit()
    Try{driver = driverFactory.createWebDriver()} match {
      case Success(_) => logger.info("New driver instance successfully created")
      case Failure(exception) => logger.info(s"Failed to create new driver instance, " +
        s"the exception was: ${exception.getMessage}")
    }
  }

  def quit(): Unit = {
    Try{driver.quit()} match {
      case Success(_) => logger.info("Driver successfully closed")
      case Failure(exception) => logger.info(s"Failed to close driver, the exception was: ${exception.getMessage}")
    }
  }
}
