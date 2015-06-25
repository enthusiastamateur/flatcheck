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
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.ie.InternetExplorerDriver
import scala.io.Source
import java.util.Calendar
import java.io.FileWriter
import java.io.File
import org.ini4j.ConfigParser
import scala.collection.JavaConversions._
import java.lang.Thread

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
    appender.write("\n" + Calendar.getInstance().getTime().toString + "\t" + link + "\t" + emailSent.toString)
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
        if (!line.isEmpty)    // skip empty  lines
      )    // split the lines
      yield (line.split("\t").toList)
      if (contents.isEmpty) List() else contents.tail     // The head is the fejlec
    } else List()
  }
}




object FlatCheck extends App {
  // Initalize parser
  val options = new ConfigParser
  options.read("flatcheck.ini")

  // read password
  val address = options.get("general","address")
  val stdIn = System.console()
  println("Please enter password for email address " + address + " :")
  val passwd = String.valueOf(stdIn.readPassword())
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
    println("  Emails sent!")
  }

  // Variable to store if an email has already been sent
  var multiplePageWarningSent =  scala.collection.mutable.Map[String,Int]().withDefaultValue(0)
  // Email setup
  val emailSubject : String = options.get("general","emailsubject")
  if (emailSubject.isEmpty) println("Email subject is not recognised!")
  val addAddresses : List[String] = options.get("general","sendto").split(",").toList
  if (addAddresses.isEmpty) println("Sendto email addresses are not recognised!")

  // Initalize the main loop
  val maxIter = options.get("general","exitafter").toInt
  val waitTime = options.get("general","refreshtime").toDouble

  // Start main loop
  mainloop(maxIter)






  /*
   *  The main loop
   */
  def mainloop(iter: Int) : Unit = {

    // Initalize browser
    val browser = options.get("general","browser").toLowerCase match
    {
      case "ie" | "internetexplorer" | "explorer" => new FluentAdapter(new InternetExplorerDriver())
      case "chrome" => new FluentAdapter(new ChromeDriver())
      case _ => new FluentAdapter(new FirefoxDriver())
    }

    def iterateThroughPages(site: String, checkedAlreadyLinks: List[String], linksAcc: List[String]) : List[String] = {
      val oldFirstHit = browser.$(options.get(site, "querystring")).get(0).getAttribute("href")
      val nextRef = browser.$(options.get(site, "nextpagestring"))    // this will be a list of hrefs if there are new pages
      nextRef.size() match {
        case 0 => return linksAcc    // This means there is no next button we can click on!
        case 1 => nextRef.click()
        case _ => nextRef.get(0).click()
      }
      val newFirstHit = browser.$(options.get(site, "querystring")).get(0).getAttribute("href") // this is the new one
      if (oldFirstHit != newFirstHit) {
        val foundHits: Int = browser.$(options.get(site, "querystring")).size()  // number of found hits on new page
        // This means successfully progressing to a new page, so harvest the links
        val newLinks = {
          for {
            i <- 0 until foundHits
            link: String = browser.$(options.get(site, "querystring")).get(i).getAttribute("href").split('?').toList(0)
            if (!checkedAlreadyLinks.contains(link))
          } yield link
        }.toList
        // Now let's go to the next page
        iterateThroughPages(site, checkedAlreadyLinks, linksAcc ::: newLinks)
      } else {
        linksAcc    // If clicking didn't get us to a new page, let's finish
      }
    }

    /*
     *  Nested function for getting new urls and saving them
     */
    def getNewURLsFromSite(site: String): List[String] = {
      if (site == "general") List() else        // The "general" tag does not correspond to a site
      {
        val filename = options.get(site,"datafile")
        try {
          browser.goTo(options.get(site, "baseurl"))
        } catch {
          case e: Exception => {
            println("Error while loading site: " + site + ". Skipping it in this iteration...")
            return List()
          }
        }
        val checkedAlready: List[List[String]] = new DataFile(filename).readFlats
        val checkedAlreadyLinks : List[String] = checkedAlready map {list => list(1)}
        try {
          // Check how many hits are on the current page and the totel number of hits reported by the site
          val foundHits: Int = browser.$(options.get(site, "querystring")).size()
          val reportedHits: Int = browser.$(options.get(site, "hitsstring")).getText.replaceAll("\\D+","").toInt
          // Get new links on the first page
          val firstNewLinks = {
            for {
              i <- 0 until foundHits
              link: String = browser.$(options.get(site, "querystring")).get(i).getAttribute("href").split('?').toList(0)
              if (!checkedAlreadyLinks.contains(link))
            } yield link
          }.toList


          if (foundHits != reportedHits)    //this means we need to check the other pages
          {
            println("  Warning: Multiple pages found for site: " + site)
          }

          val newLinks = iterateThroughPages(site,checkedAlreadyLinks,firstNewLinks)

          // Update the datafiles
          if (!newLinks.isEmpty)
          {
            val dataFile = new DataFile(filename)
            newLinks.foreach(link => dataFile.appendFlat(link,true))
            println("  Found " + newLinks.size + " new offers on site: " + site)
          }
          else
          {
            println("  No new offers found for site: " + site)
          }
          newLinks

        } catch {
          case e: Exception => {
            println("Error while querying site: " + site + ". Skipping it in this iteration...")
            return List()
          }
        }
      }
    }

    /*
     * The settings are reread each iteration. This makes it possible to edit them on the fly!
     */
    println("Beginning iteration #: " + iter)
    val sites: List[String] = options.sections().toList

    // Get new links from all sites
    val allNewLinks = sites.flatMap(getNewURLsFromSite(_))

    // Send email if there are new offers
    if (!allNewLinks.isEmpty)
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



}
