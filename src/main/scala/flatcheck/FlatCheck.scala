/**
 * Created by Tamas on 2014.11.22..
 *
 * The easiest way to use the selenium and javaMail libraries is to import them from an online repo
 * for this, go to http://mvnrepository.com and find the sbt line you have to add to your build.sbt
 * Then refresh project
 *
 */
package flatcheck

import java.io.{File, FileInputStream, FileWriter, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util.{Calendar, Scanner}

import com.machinepublishers.jbrowserdriver.{JBrowserDriver, Settings, UserAgent}
import org.apache.commons.mail._
import org.fluentlenium.adapter.FluentStandalone
import org.fluentlenium.core.domain.{FluentList, FluentWebElement}
import org.ini4j.ConfigParser
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.ie.InternetExplorerDriver
import org.openqa.selenium.{By, JavascriptExecutor, WebDriver, WebElement}
import org.openqa.selenium.NoSuchElementException

import scala.collection.JavaConverters._
import scala.io.Source
import com.typesafe.scalalogging.LazyLogging

class DataFile(filename: String) extends LazyLogging {

  // Append to end
  def appendFlat(link: String, emailSent: Boolean): Unit = {
    val file = new File(filename)
    if (!file.exists())   // write fejlec
    {
      logger.trace(s"    Creating new DataFile with name $filename")
      file.createNewFile()
      val writer = new FileWriter(file)
      writer.write("Timestamp\tID\tLink\tEmail sent")
      writer.flush()
      writer.close()
    }
    // Append to the end of file
    val appender = new FileWriter(filename,true)
    logger.trace(s"    Appending link: $link")
    appender.write("\n" + Calendar.getInstance.getTime.toString + "\t" + link + "\t" + emailSent.toString)
    appender.flush()
    appender.close()
  }

  // Read all lines
  def readFlats: List[List[String]] = {
    val file = new File(filename)
    if ( file.exists() )
    {
      val contents = for (
        line <- Source.fromFile(filename).getLines.toList
        if !line.isEmpty    // skip empty  lines
      ) yield {
        line.split("\t").toList
      }
      if (contents.isEmpty) {
        logger.info(s"    No previously checked links found in file $filename")
        List()
      } else {
        logger.trace(s"    Loaded the following entries from $filename:\n${contents.tail}")
        contents.tail
      }     // The head is the fejlec
    } else {
      logger.info(s"    No checked links file found at $filename")
      List()
    }
  }
}


class FluentWrapper(val webDriver: WebDriver) extends FluentStandalone with LazyLogging {
  override def newWebDriver() : WebDriver = {
    logger.trace(s"Passing in existing WebDriver: $webDriver")
    webDriver
  }
}

object FlatCheck extends App with LazyLogging {
  // Initalize parser
  val options = new ConfigParser
  val iniName = "flatcheck.ini"
  try {
    //val is = new InputStreamReader(new FileInputStream(iniName), StandardCharsets.UTF_8)
    options.read(new FileInputStream(iniName))
  } catch {
    case _: Exception =>
      val currDir = new File("a").getAbsolutePath.dropRight(1)
      logger.error("Couldn't read ini file: " + currDir + iniName + "\nExiting FlatCheck...")
      System.exit(1)
  }
  // read password
  val address = options.get("general","address")
  println("Please enter password for email address " + address + " :")
  val stdIn = System.console()
  val passwd : String = if (stdIn == null)
  {
    println("Warning! Flatcheck was not able to locate your console, so it will ask for your password using plan text -> your password will be visible when you type it!")
    val scanner = new Scanner(System.in)
    scanner.nextLine()
  }
  else
  {
    String.valueOf(stdIn.readPassword())
  }
  logger.info("Starting FlatCheck...")

  def sendMessage(to: List[String], subject: String, msg: String): Unit = {
    val email = new SimpleEmail()
    email.setHostName(options.get("general","hostname"))
    email.setSmtpPort(options.get("general","smtpport").toInt)
    email.setAuthenticator(new DefaultAuthenticator(address, passwd))
    email.setSSLOnConnect(options.get("general","sslonconnect").toBoolean)
    email.setFrom(address)
    email.setSubject(subject)
    to.foreach(address => email.addTo(address))
    email.setMsg(msg)
    email.send()
    logger.info("  Email sent to: " + to + "!")
  }

  // Email setup
  val emailSubject : String = options.get("general","emailsubject")
  if (emailSubject.isEmpty) println("Email subject is not recognised, so emails will be sent without a subject!")
  val addAddresses : List[String] = options.get("general","sendto").split(",").toList
  if (addAddresses.isEmpty) {
    logger.error("Sendto email addresses are not recognised! Update the ini file and restart the application! Exiting...")
    System.exit(1)
  }
  // Initalize the main loop
  val maxIter = options.get("general","exitafter").toInt
  val waitTime = options.get("general","refreshtime").toDouble

  // Load the boundary on next page clicks, because it can happen that we end up in an infinite loop
  val maxPageClicks = options.get("general","maxpageclicks").toInt

  // Start main loop
  mainloop(maxIter)

  /*
   *  The main loop, implemented as a nested function
   */
  def mainloop(iter: Int) : Unit = {
    // -------------------------------------------------
    // Initalizing main loop
    val driver : WebDriver = options.get("general","browser").toLowerCase match
    {
      case "ie" | "internetexplorer" | "explorer" => new InternetExplorerDriver()
      case "chrome" => new ChromeDriver()
      case "jbrowser" => new JBrowserDriver(Settings.builder()
        .blockAds(true)
        .headless(true)
        .javascript(true)
        .ssl("trustanything")
        .userAgent(UserAgent.CHROME)
        .build())
      case _ => new FirefoxDriver()
    }


    // Create the fluent adapter for the webdriver
    val browser = new FluentWrapper(driver)
    browser.init()
    // End of main loop initalization
    // -------------------------------------------------


    /*
     *  Nested function for getting new urls and saving them
     */
    def getNewURLsFromSite(site: String): List[String] = {
      /*
       * Nested function to iterate through all found pages
       */
      def iterateThroughPages(checkedAlreadyLinks : List[String], linksAcc: List[String], clickCount : Int) : List[String] = {
        // First scroll down to the bottom of the page.
        // Some pages load their contents dynamically, so scroll as much as needed
        val jse = driver.asInstanceOf[JavascriptExecutor]
        var lastHeight = 0.toLong
        var currHeight = jse.executeScript("return document.body.scrollHeight").asInstanceOf[Long]
        var count = 0
        val maxScrolls = try {
          options.get(site, "maxscrolls").toInt
        } catch {
          case _:Exception =>
            logger.trace("Could not read number of max scrolls for site " + site + ". Using default value of 0.")
            0
        }

        while (lastHeight != currHeight && count < maxScrolls)
        {
          jse.executeScript("window.scrollTo(0, document.body.scrollHeight);")
          logger.trace("  Scrolled down page to bottom!")
          Thread.sleep(1000)
          lastHeight = currHeight
          currHeight = jse.executeScript("return document.body.scrollHeight").asInstanceOf[Long]
          count = count + 1
        }


        // Run the CSS query sting to locate the links on the current page
        val linksSelector = options.get(site, "querystring")
        val foundLinks: FluentList[FluentWebElement] = try {
          browser.$(linksSelector)
        } catch {
          case e: Exception =>
            logger.warn(s"Error locating links on site $site using selector string: $linksSelector. The exception was:\n$e")
            return List()
        }
        // Get the number of links found
        val foundLinksSize = foundLinks.size()
        logger.info("  Located " + foundLinksSize + " offer links on current page!")

        // Get new links on the current page
        val newLinksOnPage: List[String] = {
          for {
            i <- 0 until foundLinksSize
            link: String = foundLinks.index(i).getElement.getAttribute("href").split('?').head
            if !checkedAlreadyLinks.contains(link)
          } yield link
        }.toList

        // Try to find the new page button
        val nextPageButtonSelector = options.get(site, "nextpagestring")
        logger.info(s"The nextpagestring is $nextPageButtonSelector")
        val nextPageButtonOption: Option[WebElement] = try {
          val hits = browser.$(nextPageButtonSelector)
          hits.size() match {
            case 0 =>
              logger.trace(s"  Did not find next page button with JQuery selector $nextPageButtonSelector")
              None
            case rest =>
              if (rest > 1) {
                logger.trace(s"  Found more than one next page buttons with selector:$nextPageButtonSelector. " +
                  s"Going to use the first button in the list. The hits are: $hits")
              } else {
                logger.trace(s"  Found next page button")
              }
              Some(hits.first().getElement)
          }
        } catch {
          case e: Exception =>
            logger.trace(s"  Could not find next page button using JQuery selector: $nextPageButtonSelector. The exception was:\n$e")
            None
        }

        // If there is a new page button, click it and collect the links there as well. Otherwise, return what we have acquired so far
        nextPageButtonOption match {
          case Some(nextPageButton) =>
            // Click on the new page button, and wait 5 seconds for everything to load, if we have not exhaused all the clicks
            if (clickCount < maxPageClicks) {
              logger.info(s"Clicking on next page button with text '${nextPageButton.getText}'...")
              nextPageButton.click()
              Thread.sleep(5000.toLong)
              iterateThroughPages(checkedAlreadyLinks, linksAcc ++ newLinksOnPage, clickCount + 1)
            } else {
              linksAcc ++ newLinksOnPage
            }
          case None => linksAcc ++ newLinksOnPage
        }
      }

      // -------------------------------------------------
      // Body of getNewURLsFromSite
      if (site == "general") List() else        // The "general" tag does not correspond to a site
      {
        logger.info("  --------------------------------------")
        logger.info("  Site: " + site)
        val filename = options.get(site,"datafile")
        val baseUrl = options.get(site, "baseurl")
        try {
          browser.goTo(baseUrl)
        } catch {
          case e: Exception =>
            logger.warn(s"Error loading site using URL: $baseUrl, skipping it in this iteration. The exception was:\n$e")
            return List()
        }
        val checkedAlready: List[List[String]] = new DataFile(filename).readFlats
        val checkedAlreadyLinks : List[String] = checkedAlready map {list => list(1)}

        // Iterate through all pages
        val newLinks = iterateThroughPages(checkedAlreadyLinks, List(), 0)

        // Update the datafiles
        if (newLinks.nonEmpty)
        {
          val dataFile = new DataFile(filename)
          newLinks.foreach(link => dataFile.appendFlat(link,emailSent = true))
          logger.info("  Found " + newLinks.size + " new offers!")
        }
        else
        {
          logger.info("  No new offers found!")
        }
        newLinks

      }
      // End of body of getNewURLsFromSite
      // -------------------------------------------------
    }

    // -------------------------------------------------
    // Body of main loop
    //
    // The settings are reread on each iteration. This makes is possible to edit them on the fly!
    logger.info("Beginning iteration #: " + iter)
    val sites: List[String] = options.sections().asScala.toList

    // Get new links from all sites
    val allNewLinks = sites.flatMap(getNewURLsFromSite)

    // Send email if there are new offers
    logger.info("--------------------------------------")
    if (allNewLinks.nonEmpty)
    {
      logger.info("Found " + allNewLinks.size + " new offers alltogether!")
      sendMessage(addAddresses,emailSubject,options.get("general","emailfixcontent") + " \n" + allNewLinks.mkString("\n"))
    }

    // Cleanup after iteration
    browser.quit()

    // Check if the max iteration count has been reached
    if (iter > 1) {
      logger.info("Iteration # " + iter + " finished. Waiting " + waitTime + " minutes for next iteration!")
      Thread.sleep((waitTime*60000).toLong)
      mainloop(iter - 1)
    }
  }
  // End of body of main loop
  // -------------------------------------------------
}
