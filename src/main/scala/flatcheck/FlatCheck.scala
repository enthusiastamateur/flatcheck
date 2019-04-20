/**
  * Created by Tamas on 2014.11.22..
  *
  * The easiest way to use the selenium and javaMail libraries is to import them from an online repo
  * for this, go to http://mvnrepository.com and find the sbt line you have to add to your build.sbt
  * Then refresh project
  *
  */
package flatcheck

import java.sql.SQLException
import slick.jdbc.SQLiteProfile.api._
import db._
import java.util.Scanner
import flatcheck.config.FlatcheckConfig
import org.openqa.selenium.JavascriptExecutor
import scala.collection.JavaConverters._
import com.typesafe.scalalogging.LazyLogging
import flatcheck.backup.GDriveBackup
import flatcheck.scraper.{DeepScraper, LinkScraper}

import scala.util.{Failure, Success, Try}
import java.lang.management.ManagementFactory

import flatcheck.utils.SafeDriver

object FlatCheck extends App with LazyLogging {
  // Initalize parser
  logger.info("Starting FlatCheck...")
  val arguments : List[String] = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList
  logger.info(s"The startup arguments were: ${arguments.mkString(",")}")

  val iniName = "flatcheck.ini"
  val options = new FlatcheckConfig(iniName)

  // Load the  SQLite database
  val db = Database.forConfig("flatcheck_offers")
  val offersDS : OffersDS = new OffersSQLiteDataSource(db)
  // Create connection for the interactive mode
  val conn = db.source.createConnection()



  val mode = options.safeGetString("general", "mode").toLowerCase
  mode match {
    case "prod" | "production" =>
      // Set up the backupper
      val backupper = new GDriveBackup("flatcheck.json", options.getInt("general", "syncfreqsec"), conn)
      backupper.addFile("flatcheck.ini", isText = true)
      // The database file is managed by the backupper process internally
      backupper.startBackupper()

      // Initialize the future
      val deepScraper = new DeepScraper(new FlatcheckConfig(iniName), offersDS)
      // Check if there are offers without offerdetails => these we have to start scraping
      val offersWithoutDetails = offersDS.getOffersWithoutDetails
      if (offersWithoutDetails.nonEmpty) {
        // Add the new links to the deepscraper's queue
        deepScraper.addTargets(offersWithoutDetails.map{ case (id, site, link, _, _) => (id, site, link)} )
        logger.debug(s"Added ${offersWithoutDetails.size} offers to the initial DeepScraper queue")
      } else {
        logger.debug(s"All sites have their details scraped!")
      }
      deepScraper.start()

      val linkScraper = new LinkScraper(new FlatcheckConfig(iniName), offersDS, deepScraper)
      linkScraper.start()
      val mainHeartBeatTime = options.safeGetInt("general", "mainheartbeat", Some(600))
      while (true) {
        /*
        logger.info("~~~~~~~~~~~~~~~~~~~~~~~~~~~~Runtime stats~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~")
        val mxBean = ManagementFactory.getThreadMXBean
        val threadInfos = mxBean.getThreadInfo(mxBean.getAllThreadIds)
        threadInfos.foreach{ threadInfo =>
          logger.info(s"Thread name: ${threadInfo.getThreadName}")
          logger.info(s"Thread state: ${threadInfo.getThreadState}")
          logger.info(s"Locked object this thread is waiting for to be freed: ${threadInfo.getLockName}")
          logger.info(s"Owner of the locked object: ${threadInfo.getLockOwnerName}")
          logger.info(s"Objects currently locked by this thread:\n${threadInfo.getLockedMonitors.toList.map{ info => info.getLockedStackFrame.toString}.mkString("\n\n")}")
          logger.info(s"Ownable synchronizers currently locked by this thread:\n${threadInfo.getLockedSynchronizers.toList.map{ sync => sync.toString}.mkString("\n\n")}")
          logger.info(s"Thread waited count: ${threadInfo.getWaitedCount}")
        }
        logger.info("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~")
        */
        logger.info("Main thread heatbeat")
        Thread.sleep(mainHeartBeatTime * 1000)
      }
    case "int" | "interactive" =>
      logger.info("Entered interactive Javascript interpreter mode")
      val scanner = new Scanner(System.in)
      val driver = new SafeDriver(new FlatcheckConfig(iniName), logger)
      while (true) {
        val sites: List[String] = options.sections().asScala.toList
        sites.foreach { site =>
          if (site != "general") {
            val baseUrl = options.safeGetString(site, "baseurl")
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
            driver.quit()
            logger.info(s"Moving to next page...")
          }
        }
      }
    case "sql" =>
      logger.info("Entered interactive SQL interpreter mode")
      val scanner = new Scanner(System.in)
      var line: String = null
      while ( {
        System.out.print("[SQL] > ")
        line = scanner.nextLine()
        line != "exit"
      }) {
        val res = Try({
          val stmt = conn.createStatement()
          val rs = stmt.executeQuery(line)
          val rsmd = rs.getMetaData
          val columnsNumber = rsmd.getColumnCount
          val range = 1 to columnsNumber
          System.out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~~~RESULTS~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~")
          range.foreach{ i =>
            if (i == 1) System.out.print("| ")
            System.out.print(rsmd.getColumnName(i) + " | ")
          }
          System.out.println("\n---------------------------------------------------------------------")
          while (rs.next()) {
            range.foreach{ i =>
              if (i == 1) System.out.print("| ")
              System.out.print(rs.getString(i) + " | ")
            }
            System.out.print("\n")
          }
          System.out.println("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~")
        })
        res.recover{
          case e: SQLException => System.out.println(s"SQL execution error: ${e.getMessage}")
        }
      }
      System.exit(0)

    case rest => throw new IllegalArgumentException(s"Unknown operation mode: $rest")
  }
}
