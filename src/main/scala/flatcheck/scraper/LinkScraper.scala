package flatcheck.scraper

import java.sql.Timestamp
import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import flatcheck.config.FlatcheckConfig
import flatcheck.db.{OfferDetails, OffersDS}
import flatcheck.db.Types.OfferShortId
import flatcheck.utils.{Mailer, SafeDriver}
import slick.lifted.TableQuery
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class LinkScraper(val config: FlatcheckConfig,
                  val ds: OffersDS,
                  val deepScraper: DeepScraper) extends Thread("LinkScraper") with LazyLogging {
  private val repeatTime = config.safeGetInt("general", "linkscraperrepeattime", Some(20))
  val offerDetails = TableQuery[OfferDetails]
  val mailer = new Mailer(config)
  val driver: SafeDriver = new SafeDriver(config, logger)

  def iterateThroughPages(site: String, linksAcc: List[OfferShortId], previousLinkTexts: List[String],
                          clickCount: Int, maxPageClicks: Int): List[OfferShortId] = {
    Try {
      logger.info(s"  Started new page iteration #$clickCount")
      logger.trace(s"The location is ${driver.getCurrentUrl()}")
      var count = 0
      val maxScrolls = config.safeGetInt(site, "maxscrolls", Some(0))

      var lastHeight = 0.toLong
      var currHeight = driver.executeJavascript("return document.body.scrollHeight").asInstanceOf[Long]
      while (lastHeight != currHeight && count < maxScrolls) {
        driver.executeJavascript("window.scrollTo(0, document.body.scrollHeight);")
        lastHeight = currHeight
        currHeight = driver.executeJavascript("return document.body.scrollHeight").asInstanceOf[Long]
        count = count + 1
      }

      // Set the __cfRLUnblockHandlers variable to true - this is required on cloudflare enabled sites, in case
      // the javascript could not complete successfully
      driver.executeJavascript("window.__cfRLUnblockHandlers = 1;")

      // Run the xpath query sting to locate the links on the current page
      val linksSelector = config.safeGetString(site, "linkselectorxpath")
      val foundLinks = try {
        val res = driver.findElementsByXPath(linksSelector)
        res
      } catch {
        case e: Exception =>
          logger.warn(s"  Error locating links on site $site using XPath selector string: $linksSelector. " +
            s"The exception was:\n$e")
          return List()
      }

      // Get the number of links found
      val foundLinksSize = foundLinks.size
      // Check if we are getting different results as before
      val foundLinkTexts = foundLinks.map {
        _.getAttribute("href").split('?').head
      }
      if (foundLinkTexts == previousLinkTexts) {
        if (foundLinkTexts.isEmpty) {
          logger.warn(s"Found no links on the current site! Please review the link selector...")
        } else {
          logger.warn(s"Found links are exactly the same as in the last step! Probably moving on to the next page did not work. Will stop scraping this site in this iteration...")
        }
        linksAcc
      } else {
        // Get new links on the current page
        // Save new results to DB
        val now = Timestamp.from(Instant.now())
        val linksWithIdOpts = foundLinkTexts.map { link =>
          (link, ds.getOfferIdByLink(link))
        }
        // For the already seen links, let's update the last seen time
        linksWithIdOpts.foreach { case (_, idOpt) =>
          idOpt.foreach { id => ds.updateOfferLastSeen(id, now) }
        }
        val newLinksOnPage = linksWithIdOpts.collect { case (link, idOpt) if idOpt.isEmpty =>
          (ds.addOffer((0, site, link, now, now)), site, link)
        }

        logger.info("  Located " + foundLinksSize + s" offer links on current page, out of which ${newLinksOnPage.size} was new!")

        // Try to find the new page button
        val nextPageButtonSelector = config.safeGetString(site, "nextbuttonselectorxpath")
        // Click on the new page button, and wait 5 seconds for everything to load, if we have not exhausted all the clicks
        if (clickCount < maxPageClicks) {
          if (driver.clickElementByXPath(nextPageButtonSelector)) {
            iterateThroughPages(site, linksAcc ++ newLinksOnPage, foundLinkTexts, clickCount + 1, maxPageClicks)
          } else {
            logger.trace(s"Button click was ineffective, returning with results")
            linksAcc ++ newLinksOnPage
          }
        } else {
          logger.debug(s"Reached maximum number of new page clicks ($maxPageClicks), returning with results")
          linksAcc ++ newLinksOnPage
        }
      }
    } match {
      case Success(value) => value
      case Failure(exception) =>
        logger.warn(s"Could not get new links from site $site (clickCount = $clickCount), the exception was: ${exception.getMessage}. Returning the accumulated links...")
        linksAcc
    }
  }

  def scrapeNewURLsFromSite(site: String): Unit = {
    val skipSites = config.safeGetString("general","skipsites", Some("")).split(",")
    if (skipSites.contains(site)) {
      logger.trace(s"Skipping site $site because it is marked for skip")
    } else {
      if (site != "general") // The "general" tag does not correspond to a site
      {
        Try {
          driver.reset()
          logger.info("  --------------------------------------")
          logger.info("  Site: " + site)
          val baseUrl = config.safeGetString(site, "baseurl")
          val waitTime = config.safeGetInt(site, "linkscraperwait", Some(1))
          val maxPageClicks: Int = config.safeGetInt(site, "maxpageclicks", Some(10))
          driver.get(baseUrl)
          logger.info(s"Waiting $waitTime seconds for page to load")
          Thread.sleep(waitTime * 1000)
          // Iterate through all pages
          val newLinks = iterateThroughPages(site, List(), List(), 0, maxPageClicks)

          // Update the datafiles
          if (newLinks.nonEmpty) {
            logger.info("  Found " + newLinks.size + " new offers!")
            // Add the new links to the deepscraper's queue
            deepScraper.addTargets(newLinks)
          } else {
            logger.info("  No new offers found!")
          }
        } match {
          case Success(_) => logger.trace(s"Successfully finished getting URLs from site $site")
          case Failure(exception) =>
            logger.warn(s"Could not get new links from site $site, the exception was: ${exception.getMessage}. Proceeding with next site...")
        }
      }
    }
  }

  def scanAllSites(iter: Int): Unit = {
    Try {
      config.reRead()
      val sites: List[String] = config.sections().asScala.toList
      sites.foreach(scrapeNewURLsFromSite)
    } match {
      case Success(_) =>
        logger.info(s"Iteration # $iter finished. Waiting $repeatTime seconds for next iteration!")
      case Failure(exception) =>
        logger.warn(s"Iteration # $iter failed with excpetion ${exception.getMessage}. Waiting $repeatTime seconds for next iteration!")
    }
    Thread.sleep((repeatTime * 1000).toLong)
  }

  override def run(): Unit = {
    logger.info(s"Started linkscraper")
    var iter: Int = 1
    while (true) {
      scanAllSites(iter)
      iter = iter + 1
    }
  }
}
