/**
 * Created by Tamas on 2014.11.22..
 *
 * The easiest way to use the selenium and javaMail libraries is to import them from an online repo
 * for this, go to http://mvnrepository.com and find the sbt line you have to add to your build.sbt
 * Then refresh project
 *
 */

import org.apache.commons.mail._
import org.fluentlenium.core.FluentAdapter
import org.fluentlenium.core.domain.{FluentList, FluentWebElement}
import org.openqa.selenium.{JavascriptExecutor, By, WebElement, WebDriver}
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.ie.InternetExplorerDriver
import java.util.Calendar
import java.io.FileWriter
import java.io.File
import org.ini4j.ConfigParser
import scala.collection.JavaConversions._
import java.lang.Thread
import java.util.Scanner

class DataFile(filename: String) {

  // Append to end
  def appendFlat(link: String, emailSent: Boolean): Unit = {
    val file = new File(filename)
    if (!file.exists())   // write fejlec
    {
      file.createNewFile()
      val writer = new FileWriter(file)
      writer.write("Timestamp\tID\tLink\tEmail sent")
      writer.flush()
      writer.close()
    }
    // Append to the end of file
    val appender = new FileWriter(filename,true)
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
        line <- io.Source.fromFile(filename).getLines.toList
        if line.isEmpty    // skip empty  lines
      )    // split the lines
      yield line.split("\t").toList
      if (contents.isEmpty) {
        println("  No previously checked links found in file " + filename)
        List()
      } else contents.tail     // The head is the fejlec
    } else {
      println("  No checked links file found at " + filename )
      List()
    }
  }
}




object FlatCheck extends App {
  // Initalize parser
  val options = new ConfigParser
  val iniName = "flatcheck.ini"
  try {
    options.read(iniName)
  } catch {
    case e: Exception =>
      val currDir = new File("a").getAbsolutePath.dropRight(1)
      println("Couldn't read ini file: " + currDir + iniName + "\nExiting FlatCheck...")
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
  println("Starting FlatCheck...")

  def sendMessage(to: List[String], subject: String, msg: String) = {
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
    println("  Email sent to: " + to + "!")
  }

  // Email setup
  val emailSubject : String = options.get("general","emailsubject")
  if (emailSubject.isEmpty) println("Email subject is not recognised, so emails will be sent without a subject!")
  val addAddresses : List[String] = options.get("general","sendto").split(",").toList
  if (addAddresses.isEmpty) {
    println("Sendto email addresses are not recognised! Update the ini file and restart the application! Exiting...")
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
      case _ => new FirefoxDriver()
    }

    // Create the fluent adapter for the webdriver
    val browser = new FluentAdapter(driver)
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
          case e:Exception =>
            println("  Could not read number of max scrolls for site " + site + ". Using default value of 0.")
            0
        }

        while (lastHeight != currHeight && count < maxScrolls)
        {
          jse.executeScript("window.scrollTo(0, document.body.scrollHeight);")
          println("  Scrolled down page to bottom!")
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
            println("  Error locating links on site \"" + site + "\" using selector string: " + linksSelector)
            return List()
        }
        // Get the number of links found
        val foundLinksSize = foundLinks.size()
        println("  Located " + foundLinksSize + " offer links on current page!")

        // Get new links on the current page
        val newLinksOnPage: List[String] = {
          for {
            i <- 0 until foundLinksSize
            link: String = foundLinks.get(i).getAttribute("href").split('?').head
            if !checkedAlreadyLinks.contains(link)
          } yield link
        }.toList

        // Try to find the new page button
        val nextPageButtonSelector = options.get(site, "nextpagestring")
        val nextPageButtonOption: Option[WebElement] = try {
          // First try locating using xpath
          Some(driver.findElement(By.xpath(nextPageButtonSelector)))
        } catch {
          case e: Exception =>
            // Try also using CSS selector
            val buttonOpt: Option[WebElement] = try {
              Some(driver.findElement(By.cssSelector(nextPageButtonSelector)))
            } catch {
              case e: Exception =>
                println("  Next page button not found!")
                None
            }
            buttonOpt
        }

        // If there is a new page button, click it and collect the links there as well. Otherwise, return what we have acquired so far
        nextPageButtonOption match {
          case Some(nextPageButton) =>
            // Click on the new page button, and wait 5 seconds for everything to load, if we have not exhaused all the clicks
            if (clickCount < maxPageClicks) {
              println("  Clicking on next page button with text '" + nextPageButton.getText + "'...")
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
        println("  --------------------------------------")
        println("  Site: " + site)
        val filename = options.get(site,"datafile")
        val baseUrl = options.get(site, "baseurl")
        try {
          browser.goTo(baseUrl)
        } catch {
          case e: Exception =>
            println("  Error loading site using URL:\n  " + baseUrl + "\n  Please check manually if it works!" +
              "\n  Skipping it in this iteration...")
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
          println("  Found " + newLinks.size + " new offers!")
        }
        else
        {
          println("  No new offers found!")
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
    println("Beginning iteration #: " + iter)
    val sites: List[String] = options.sections().toList

    // Get new links from all sites
    val allNewLinks = sites.flatMap(getNewURLsFromSite)

    // Send email if there are new offers
    println("--------------------------------------")
    if (allNewLinks.nonEmpty)
    {
      println("  Found " + allNewLinks.size + " new offers alltogether!")
      sendMessage(addAddresses,emailSubject,options.get("general","emailfixcontent") + " \n" + allNewLinks.mkString("\n"))
    }

    // Cleanup after iteration
    browser.quit()

    // Check if the max iteration count has been reached
    if (iter > 1) {
      println("Iteration # " + iter + " finished. Waiting " + waitTime + " minutes for next iteration!")
      Thread.sleep((waitTime*60000).toLong)
      mainloop(iter - 1)
    }
  }
  // End of body of main loop
  // -------------------------------------------------
}
