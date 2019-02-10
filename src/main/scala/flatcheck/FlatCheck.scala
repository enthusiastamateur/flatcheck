/**
  * Created by Tamas on 2014.11.22..
  *
  * The easiest way to use the selenium and javaMail libraries is to import them from an online repo
  * for this, go to http://mvnrepository.com and find the sbt line you have to add to your build.sbt
  * Then refresh project
  *
  */
package flatcheck

import java.io.{File, FileInputStream, FileWriter}
import java.sql.DriverManager
import java.util.{Calendar, Scanner}

import com.machinepublishers.jbrowserdriver.{JBrowserDriver, Settings, UserAgent}
import org.apache.commons.mail._
import org.ini4j.ConfigParser
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.ie.InternetExplorerDriver
import org.openqa.selenium.{By, JavascriptExecutor, WebDriver, WebElement}

import scala.collection.JavaConverters._
import scala.io.Source
import com.typesafe.scalalogging.LazyLogging
import flatcheck.backup.GDriveBackup
import javax.mail.AuthenticationFailedException

import scala.util.{Failure, Success, Try}

class DataFile(filename: String) extends LazyLogging {

  // Append to end
  def appendFlat(link: String, emailSent: Boolean): Unit = {
    val file = new File(filename)
    if (!file.exists()) // write fejlec
    {
      logger.trace(s"    Creating new DataFile with name $filename")
      file.createNewFile()
      val writer = new FileWriter(file)
      writer.write("Timestamp\tID\tLink\tEmail sent")
      writer.flush()
      writer.close()
    }
    // Append to the end of file
    val appender = new FileWriter(filename, true)
    logger.trace(s"    Appending link: $link")
    appender.write("\n" + Calendar.getInstance.getTime.toString + "\t" + link + "\t" + emailSent.toString)
    appender.flush()
    appender.close()
  }

  // Read all lines
  def readFlats: List[List[String]] = {
    val file = new File(filename)
    if (file.exists()) {
      val contents = for (
        line <- Source.fromFile(filename).getLines.toList
        if !line.isEmpty // skip empty  lines
      ) yield {
        line.split("\t").toList
      }
      if (contents.isEmpty) {
        logger.info(s"    No previously checked links found in file $filename")
        List()
      } else {
        logger.trace(s"    Loaded ${contents.tail.size} entries from $filename")
        contents.tail
      } // The head is the fejlec
    } else {
      logger.info(s"    No checked links file found at $filename")
      List()
    }
  }
}

object FlatCheck extends App with LazyLogging {
  def testCredentials(): Unit = {
    logger.info("Verifying SMTP credentials...")
    val email = new SimpleEmail()
    email.setHostName(options.get("general", "hostname"))
    email.setSmtpPort(options.get("general", "smtpport").toInt)
    email.setAuthenticator(new DefaultAuthenticator(address, passwd))
    email.setSSLOnConnect(options.get("general", "sslonconnect").toBoolean)
    Try(email.getMailSession.getTransport().connect()) match {
      case Failure(_: AuthenticationFailedException) =>
        logger.error(s"Authentication has failed, " +
        s"please check the email address and password in the flatcheck.ini file!")
        System.exit(1)
      case Failure(e) => throw e
      case Success(_) => logger.info("SMTP credentials successfully verified!")
    }
  }

  // Initalize parser
  logger.info("Starting FlatCheck...")
  val backupper = new GDriveBackup("flatcheck.json")
  backupper.syncFile("flatcheck.ini", isText = true)
  backupper.syncFile("flatcheck_offers.db", isText = false)

  val iniName = "flatcheck.ini"
  val options = new ConfigParser
  try {
    //val is = new InputStreamReader(new FileInputStream(iniName), StandardCharsets.UTF_8)
    options.read(new FileInputStream(iniName))
  } catch {
    case _: Exception =>
      val currDir = new File("a").getAbsolutePath.dropRight(1)
      logger.error("Couldn't read ini file: " + currDir + iniName + "\nExiting FlatCheck...")
      System.exit(1)
  }
  val address = options.get("general", "address")
  val passwd: String = options.get("general", "emailpassword")

  def sendMessage(to: List[String], subject: String, msg: String): Unit = {
    val email = new SimpleEmail()
    email.setHostName(options.get("general", "hostname"))
    email.setSmtpPort(options.get("general", "smtpport").toInt)
    email.setAuthenticator(new DefaultAuthenticator(address, passwd))
    email.setSSLOnConnect(options.get("general", "sslonconnect").toBoolean)
    email.setFrom(address)
    email.setSubject(subject)
    to.foreach(address => email.addTo(address))
    email.setMsg(msg)
    email.send()
    logger.info("  Email sent to: " + to + "!")
  }

  // Email setup
  val emailSubject: String = options.get("general", "emailsubject")
  if (emailSubject.isEmpty) println("Email subject is not recognised, so emails will be sent without a subject!")
  val addAddresses: List[String] = options.get("general", "sendto").split(",").toList
  if (addAddresses.isEmpty) {
    logger.error("Sendto email addresses are not recognised! Update the ini file and restart the application! Exiting...")
    System.exit(1)
  }
  // Initalize the main loop
  val maxIter = options.get("general", "exitafter").toInt
  val waitTime = options.get("general", "refreshtime").toDouble

  // Load the boundary on next page clicks, because it can happen that we end up in an infinite loop
  val maxPageClicks = options.get("general", "maxpageclicks").toInt

  // Load the  SQLite database
  val dbFileName = options.get("general", "sqldb")
  val conn = DriverManager.getConnection(s"jdbc:sqlite:$dbFileName")
  // Test the credentials
  testCredentials()
  // Start main loop
  mainloop(maxIter)

  /*
   *  The main loop, implemented as a nested function
   */
  def mainloop(iter: Int): Unit = {
    // -------------------------------------------------
    // Initalizing main loop
    val driver: WebDriver = options.get("general", "browser").toLowerCase match {
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
    // End of main loop initalization
    // -------------------------------------------------


    /*
     *  Nested function for getting new urls and saving them
     */
    def getNewURLsFromSite(site: String): List[String] = {
      /*
       * Nested function to iterate through all found pages
       */
      def iterateThroughPages(checkedAlreadyLinks: List[String], linksAcc: List[String], clickCount: Int): List[String] = {
        // First scroll down to the bottom of the page.
        // Some pages load their contents dynamically, so scroll as much as needed
        val jse = driver.asInstanceOf[JavascriptExecutor]
        var lastHeight = 0.toLong
        var currHeight = jse.executeScript("return document.body.scrollHeight").asInstanceOf[Long]
        var count = 0
        val maxScrolls = try {
          options.get(site, "maxscrolls").toInt
        } catch {
          case _: Exception =>
            logger.trace("  Could not read number of max scrolls for site " + site + ". Using default value of 0.")
            0
        }

        while (lastHeight != currHeight && count < maxScrolls) {
          jse.executeScript("window.scrollTo(0, document.body.scrollHeight);")
          logger.trace("  Scrolled down page to bottom!")
          Thread.sleep(1000)
          lastHeight = currHeight
          currHeight = jse.executeScript("return document.body.scrollHeight").asInstanceOf[Long]
          count = count + 1
        }


        // Run the CSS query sting to locate the links on the current page
        val linksSelector = options.get(site, "linkselectorxpath")
        val foundLinks = try {
          driver.findElements(By.xpath(linksSelector)).asScala.toList
        } catch {
          case e: Exception =>
            logger.warn(s"  Error locating links on site $site using XPath selector string: $linksSelector. " +
              s"The exception was:\n$e")
            return List()
        }
        // Get the number of links found
        val foundLinksSize = foundLinks.size
        logger.info("  Located " + foundLinksSize + " offer links on current page!")

        // Get new links on the current page
        val newLinksOnPage: List[String] = {
          for {
            elem <- foundLinks
            link = elem.getAttribute("href").split('?').head
            if !checkedAlreadyLinks.contains(link)
          } yield link
        }

        // Try to find the new page button
        val nextPageButtonSelector = options.get(site, "nextbuttonselectorxpath")
        val nextPageButtonOption: Option[WebElement] = try {
          val hits  = driver.findElements(By.xpath(nextPageButtonSelector)).asScala.toList
          hits.size match {
            case 0 =>
              logger.trace(s"  Did not find next page button with XPath selector $nextPageButtonSelector")
              None
            case rest =>
              if (rest > 1) {
                logger.trace(s"  Found more than one next page buttons with XPath selector:$nextPageButtonSelector. " +
                  s"Going to use the first button in the list. The hits are: $hits")
              } else {
                logger.trace(s"  Found next page button")
              }
              Some(hits.head)
          }
        } catch {
          case e: Exception =>
            logger.trace(s"  Could not find next page button using JQuery selector: $nextPageButtonSelector. The exception was:\n$e")
            None
        }

        // If there is a new page button, click it and collect the links there as well. Otherwise, return what we have acquired so far
        nextPageButtonOption match {
          case Some(nextPageButton) =>
            // Click on the new page button, and wait 5 seconds for everything to load, if we have not exhausted all the clicks
            if (nextPageButton.isEnabled && clickCount < maxPageClicks) {
              logger.info(s"  Clicking on next page button with text '${nextPageButton.getText}'...")
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
      if (site == "general") List() else // The "general" tag does not correspond to a site
      {
        logger.info("  --------------------------------------")
        logger.info("  Site: " + site)
        val filename = options.get(site, "datafile")
        val baseUrl = options.get(site, "baseurl")
        try {
          driver.get(baseUrl)
        } catch {
          case e: Exception =>
            logger.warn(s"Error loading site using URL: $baseUrl, skipping it in this iteration. The exception was:\n$e")
            return List()
        }
        val checkedAlready: List[List[String]] = new DataFile(filename).readFlats
        val checkedAlreadyLinks: List[String] = checkedAlready map { list => list(1) }

        // Iterate through all pages
        val newLinks = iterateThroughPages(checkedAlreadyLinks, List(), 0)

        // Update the datafiles
        if (newLinks.nonEmpty) {
          val dataFile = new DataFile(filename)
          newLinks.foreach(link => dataFile.appendFlat(link, emailSent = true))
          logger.info("  Found " + newLinks.size + " new offers!")
        }
        else {
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
    val mode = options.get("general", "mode").toLowerCase
    mode match {
      case "prod" | "production" =>
        val allNewLinks = sites.flatMap(getNewURLsFromSite)

        // Send email if there are new offers
        logger.info("--------------------------------------")
        if (allNewLinks.nonEmpty) {
          logger.info("Found " + allNewLinks.size + " new offers alltogether!")
          sendMessage(addAddresses, emailSubject, options.get("general", "emailfixcontent") + " \n" + allNewLinks.mkString("\n"))
        }

        val thread = new Thread("deep-scraper") {
          override def run() {
            Thread.sleep(5000)
            logger.info(s"Spawning thread for deep link scraping...")
          }
        }
        thread.start()

        // Check if the max iteration count has been reached
        if (iter > 1) {
          logger.info("Iteration # " + iter + " finished. Waiting " + waitTime + " minutes for next iteration!")
          Thread.sleep((waitTime * 60000).toLong)
          mainloop(iter - 1)
        }
      case "int" | "interactive" =>
        logger.info("Entered interactive Javascript interpreter mode")
        val scanner = new Scanner(System.in)
        sites.foreach { site =>
          if (site != "general") {
            val baseUrl = options.get(site, "baseurl")
            logger.info(s"Visiting site $site at $baseUrl")
            driver.get(baseUrl)
            var line: String = null
            while ( {
              System.out.print(s"[$site] > ")
              line = scanner.nextLine()
              line != "//next"
            }) {
              val res = Try({
                val jse = driver.asInstanceOf[JavascriptExecutor]
                jse.executeAsyncScript(line)
              })
              //val jsConsoleLogs = driver.manage().logs().get("javascript").getAll.asScala.map{entry => entry.toString}.toList
              //logger.info(s"The javascript console logs:\n\n$jsConsoleLogs\n\n")
              res match {
                case Success(result) =>
                  logger.info(s"Script returned value: $result")
                case Failure(exception) =>
                  logger.info(s"Encountered exception: $exception")
              }
            }
            logger.info(s"Moving to next page...")
          }
        }
        if (iter > 1) {
          logger.info("Iteration # " + iter + " finished")
          mainloop(iter - 1)
        }
      case rest => throw new IllegalArgumentException(s"Unknown operation mode: $rest")
    }
  }
}
